package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.SlowConsumerException
import ai.tatawur.rafiq.bridge.store.Subscription
import ai.tatawur.rafiq.protocol.AgentThread
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.ThreadKind
import ai.tatawur.rafiq.protocol.api.PushKind
import ai.tatawur.rafiq.protocol.api.PushPayload
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for [PushNotifier]. The defaults are what the bridge runs with; tests shorten nothing, they use virtual time.
 *
 * @property turnDoneDebounce how long a turn must stay over before [PushKind.TURN_DONE] goes out, so an agent that
 *   stops and starts again at once does not buzz the phone.
 * @property attempts how many times one push is tried in all.
 * @property backoff the wait before the second try, doubled before each later one.
 * @property ratePerDevice at most this many turn and error pushes per device in [rateWindow]. Past it they are dropped.
 * @property approvalRatePerDevice the same for approvals and their clearing, in a bucket of their own, so a chatty
 *   agent never uses up the room an approval needs, and an agent looping on approvals is still capped.
 * @property sendTimeout the longest one try may take, whatever the transport does.
 * @property subscriptionCapacity how many events may wait for the notifier. Every event lands here, message deltas
 *   included, and the notifier filters by kind itself, so this is sized for a burst of streaming text.
 */
data class PushSettings(
    val turnDoneDebounce: Duration = 3.seconds,
    val attempts: Int = 3,
    val backoff: Duration = 2.seconds,
    val ratePerDevice: Int = 30,
    val approvalRatePerDevice: Int = 120,
    val rateWindow: Duration = 1.minutes,
    val sendTimeout: Duration = 15.seconds,
    val queuePerDevice: Int = 64,
    val subscriptionCapacity: Int = 16_384,
    val approvalTtlSeconds: Long = 3600,
    val otherTtlSeconds: Long = 3600,
)

/**
 * Sends a push to each phone that registered for them when an agent needs you: an approval opens or goes away, the
 * main thread's turn ends, or a session or thread fails. Which events count is [PushTriggers]'s job.
 *
 * - It reads every session's live events from [subscribe], never the stored history, so starting the bridge
 *   pushes nothing about the past.
 * - Every registered, active device gets the push when [visible] says the session is listed.
 * - Each device has its own queue and worker, so a slow push service for one phone never delays another, and
 *   one phone's pushes leave in order.
 * - A push is tried [PushSettings.attempts] times with backoff. A 404 or 410 deletes the registration.
 * - The payload holds ids and a kind only, encrypted for the phone (RFC 8291).
 * - Nothing logged here holds an endpoint.
 *
 * @param subscribe opens a live feed of every session's new events.
 * @param threadsOf a session's threads, to find the main thread of a session that began before the notifier did.
 * @param dispatcher runs the notifier, its debounce timers and its device workers. It must run one task at a time,
 *   because they share plain state.
 * @param nowMs the clock for the per-device rate cap.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Parameters are test seams; each function is one step of a push.
class PushNotifier(
    private val subscribe: suspend (capacity: Int) -> Subscription,
    private val threadsOf: suspend (sessionId: String) -> List<AgentThread>,
    private val store: DeviceStore,
    private val visible: (sessionId: String) -> Boolean,
    private val transport: PushTransport,
    private val scope: CoroutineScope,
    private val settings: PushSettings = PushSettings(),
    private val encryptor: WebPushEncryptor = WebPushEncryptor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val triggers = PushTriggers()
    private val pendingTurnDone = HashMap<String, Job>()
    private val queues = HashMap<String, Channel<PushPayload>>()

    /** The devices that have a worker now. For tests and diagnostics; read it on [dispatcher]. */
    internal val devicesWithWorkers: Set<String> get() = queues.keys.toSet()

    /** The send times in each of a device's two rate windows. */
    private class RateBuckets {
        val approvals = ArrayDeque<Long>()
        val others = ArrayDeque<Long>()
    }

    /**
     * Subscribes and starts pushing. Call it after start-up recovery, so the events recovery writes are not live.
     * Returns once the subscription is open, so an event appended after this returns is seen.
     */
    suspend fun start(): Job {
        val first = subscribe(settings.subscriptionCapacity)
        return scope.launch(dispatcher) { run(first) }
    }

    @Suppress("TooGenericExceptionCaught") // No failure may stop pushes for good: log the class, carry on.
    private suspend fun run(first: Subscription) {
        var subscription: Subscription? = first
        while (scope.isActive) {
            try {
                val current = subscription ?: subscribe(settings.subscriptionCapacity).also { subscription = it }
                for (event in current.events) onEventSafely(event)
                return
            } catch (cancelled: CancellationException) {
                subscription?.close()
                throw cancelled
            } catch (_: SlowConsumerException) {
                // The log closed the feed. Missed events stay missed: replaying them would send stale pushes, which
                // are worse than none. Carry on with new events.
                logger.warn("The push notifier fell behind the event log and skipped some events.")
                subscription = null
            } catch (failure: Exception) {
                // Subscribing failed. Wait before the next try, so a broken log does not spin.
                logger.warn("The push notifier could not subscribe: {}", failure.javaClass.simpleName)
                subscription?.close()
                subscription = null
                delay(settings.backoff)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // One bad event is skipped, never the end of the feed.
    private suspend fun onEventSafely(event: Event) {
        try {
            onEvent(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("The push notifier skipped an event it failed on: {}", failure.javaClass.simpleName)
        }
    }

    private suspend fun onEvent(event: Event) {
        if (triggers.isNew(event.sessionId)) {
            threadsOf(event.sessionId)
                .firstOrNull { it.kind == ThreadKind.MAIN }
                ?.let { triggers.knowMainThread(event.sessionId, it.id) }
        }
        for (decision in triggers.onEvent(event)) {
            when (decision) {
                is PushDecision.Send -> {
                    fanOut(decision.payload())
                }

                is PushDecision.TurnEnded -> {
                    pendingTurnDone.remove(decision.sessionId)?.cancel()
                    pendingTurnDone[decision.sessionId] =
                        scope.launch(dispatcher) {
                            delay(settings.turnDoneDebounce)
                            pendingTurnDone.remove(decision.sessionId)
                            if (triggers.turnStillOver(decision.sessionId)) {
                                fanOut(PushPayload(kind = PushKind.TURN_DONE, sessionId = decision.sessionId))
                            }
                        }
                }

                is PushDecision.TurnResumed -> {
                    pendingTurnDone.remove(decision.sessionId)?.cancel()
                }
            }
        }
    }

    private suspend fun fanOut(payload: PushPayload) {
        if (!visible(payload.sessionId)) return
        val devices = store.pushDevices()
        // A device revoked or unregistered while its worker sat idle: end the worker and drop its queue.
        queues.keys.filterNot(devices::contains).forEach { gone -> queues.remove(gone)?.close() }
        for (deviceId in devices) {
            val queue = queues.getOrPut(deviceId) { worker(deviceId) }
            if (queue.trySend(payload).isFailure) {
                logger.warn("Dropped a {} push for device {}: its queue is full.", payload.kind, deviceId)
            }
        }
    }

    /**
     * Starts the worker that sends [deviceId]'s pushes one at a time, and returns its queue. The worker ends, and its
     * queue goes, once the device has no registration; a later registration starts a new one.
     */
    private fun worker(deviceId: String): Channel<PushPayload> {
        val queue = Channel<PushPayload>(settings.queuePerDevice)
        scope.launch(dispatcher) {
            val buckets = RateBuckets()
            for (payload in queue) {
                if (!sendOne(deviceId, payload, buckets)) {
                    if (queues[deviceId] === queue) queues.remove(deviceId)
                    queue.close()
                    break
                }
            }
        }
        return queue
    }

    /** Sends one push unless the rate cap drops it. False when the device has no registration any more. */
    @Suppress("TooGenericExceptionCaught") // One push that fails in any way must not stop the device's later ones.
    private suspend fun sendOne(
        deviceId: String,
        payload: PushPayload,
        buckets: RateBuckets,
    ): Boolean {
        // Approvals and their clearing are what push is for, so they have their own, larger bucket. It still stops
        // an agent that loops on approvals from flooding the push service.
        val approval = payload.kind in APPROVAL_KINDS
        val over =
            if (approval) {
                overRate(buckets.approvals, settings.approvalRatePerDevice)
            } else {
                overRate(buckets.others, settings.ratePerDevice)
            }
        if (over) {
            logger.warn("Dropped a {} push for device {}: over the rate cap.", payload.kind, deviceId)
            return true
        }
        return try {
            deliver(deviceId, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn("A {} push for device {} failed: {}", payload.kind, deviceId, failure.javaClass.simpleName)
            true
        }
    }

    /** A sliding window over the times of the pushes already sent. Records this one when it may go. */
    private fun overRate(
        sentAtMs: ArrayDeque<Long>,
        limit: Int,
    ): Boolean {
        val now = nowMs()
        val window = settings.rateWindow.inWholeMilliseconds
        while (sentAtMs.isNotEmpty() && now - sentAtMs.first() >= window) sentAtMs.removeFirst()
        if (sentAtMs.size >= limit) return true
        sentAtMs.addLast(now)
        return false
    }

    /** Tries [payload] with backoff. False when the device turned out to have no registration. */
    private suspend fun deliver(
        deviceId: String,
        payload: PushPayload,
    ): Boolean {
        val plaintext = protocolJson.encodeToString(PushPayload.serializer(), payload).toByteArray()
        var wait = settings.backoff
        var tries = 0
        var result = Attempt.RETRY
        while (result == Attempt.RETRY && tries < settings.attempts) {
            if (tries > 0) {
                delay(wait)
                wait *= 2
            }
            tries++
            result = attempt(deviceId, payload, plaintext)
        }
        if (result == Attempt.RETRY) {
            logger.warn("Gave up on a {} push for device {} after {} tries.", payload.kind, deviceId, tries)
        }
        return result != Attempt.UNREGISTERED
    }

    private enum class Attempt { DONE, RETRY, UNREGISTERED }

    /** One try. */
    private suspend fun attempt(
        deviceId: String,
        payload: PushPayload,
        plaintext: ByteArray,
    ): Attempt {
        // Read before every try, not when the push was queued, so an unregister or a revoke stops it at once.
        val target = store.push(deviceId) ?: return Attempt.UNREGISTERED
        val approval = payload.kind == PushKind.APPROVAL
        val ttl = if (approval) settings.approvalTtlSeconds else settings.otherTtlSeconds
        val urgency = if (approval) PushUrgency.HIGH else PushUrgency.NORMAL
        val body = encryptor.encrypt(plaintext, target.p256dh, target.auth)
        // A backstop for any transport: a send that never returns must not hold the device's queue.
        val outcome =
            withTimeoutOrNull(settings.sendTimeout) { transport.send(target.endpoint, body, ttl, urgency) }
                ?: SendOutcome.Retry("timed out")
        return when (outcome) {
            SendOutcome.Accepted -> {
                Attempt.DONE
            }

            SendOutcome.Gone -> {
                logger.info("The push service no longer knows device {}. Its registration is deleted.", deviceId)
                store.deletePush(deviceId, target.endpoint)
                Attempt.UNREGISTERED
            }

            is SendOutcome.Failed -> {
                logger.warn("A {} push for device {} was refused: {}", payload.kind, deviceId, outcome.reason)
                Attempt.DONE
            }

            is SendOutcome.Retry -> {
                logger.debug("A {} push for device {} is tried again: {}", payload.kind, deviceId, outcome.reason)
                Attempt.RETRY
            }
        }
    }

    private fun PushDecision.Send.payload() = PushPayload(kind = kind, sessionId = sessionId, approvalId = approvalId)

    private companion object {
        val logger = LoggerFactory.getLogger(PushNotifier::class.java)

        /** Kinds counted in the approval bucket, not the general one. */
        val APPROVAL_KINDS = setOf(PushKind.APPROVAL, PushKind.APPROVAL_CLEARED)
    }
}
