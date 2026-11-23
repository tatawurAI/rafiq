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
 * @property ratePerDevice at most this many pushes per device in [rateWindow]. Past it they are dropped.
 */
data class PushSettings(
    val turnDoneDebounce: Duration = 3.seconds,
    val attempts: Int = 3,
    val backoff: Duration = 2.seconds,
    val ratePerDevice: Int = 30,
    val rateWindow: Duration = 1.minutes,
    val queuePerDevice: Int = 64,
    val subscriptionCapacity: Int = 1024,
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
@Suppress("LongParameterList") // Each is a seam the tests replace.
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

    /**
     * Subscribes and starts pushing. Call it after start-up recovery, so the events recovery writes are not live.
     * Returns once the subscription is open, so an event appended after this returns is seen.
     */
    suspend fun start(): Job {
        val first = subscribe(settings.subscriptionCapacity)
        return scope.launch(dispatcher) { run(first) }
    }

    @Suppress("TooGenericExceptionCaught") // A failure on one event must not stop pushes for good.
    private suspend fun run(first: Subscription) {
        var subscription = first
        while (scope.isActive) {
            try {
                for (event in subscription.events) onEvent(event)
                return
            } catch (cancelled: CancellationException) {
                subscription.close()
                throw cancelled
            } catch (slow: SlowConsumerException) {
                // Events were missed. Pushes are best effort, so carry on with the next ones.
                logger.warn("The push notifier fell behind the event log and missed some events: {}", slow.message)
                subscription = subscribe(settings.subscriptionCapacity)
            } catch (failure: Exception) {
                logger.warn("The push notifier failed on an event: {}", failure.javaClass.simpleName)
                subscription.close()
                subscription = subscribe(settings.subscriptionCapacity)
            }
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
        for (deviceId in store.pushDevices()) {
            val queue = queues.getOrPut(deviceId) { worker(deviceId) }
            if (queue.trySend(payload).isFailure) {
                logger.warn("Dropped a {} push for device {}: its queue is full.", payload.kind, deviceId)
            }
        }
    }

    /** Starts the worker that sends [deviceId]'s pushes one at a time, and returns its queue. */
    private fun worker(deviceId: String): Channel<PushPayload> {
        val queue = Channel<PushPayload>(settings.queuePerDevice)
        scope.launch(dispatcher) {
            val sentAtMs = ArrayDeque<Long>()
            for (payload in queue) {
                if (overRate(sentAtMs)) {
                    logger.warn("Dropped a {} push for device {}: over the rate cap.", payload.kind, deviceId)
                    continue
                }
                deliver(deviceId, payload)
            }
        }
        return queue
    }

    /** A sliding window over the times of the pushes already sent. Records this one when it may go. */
    private fun overRate(sentAtMs: ArrayDeque<Long>): Boolean {
        val now = nowMs()
        val window = settings.rateWindow.inWholeMilliseconds
        while (sentAtMs.isNotEmpty() && now - sentAtMs.first() >= window) sentAtMs.removeFirst()
        if (sentAtMs.size >= settings.ratePerDevice) return true
        sentAtMs.addLast(now)
        return false
    }

    private suspend fun deliver(
        deviceId: String,
        payload: PushPayload,
    ) {
        val plaintext = protocolJson.encodeToString(PushPayload.serializer(), payload).toByteArray()
        var wait = settings.backoff
        var tries = 0
        var done = false
        while (!done && tries < settings.attempts) {
            if (tries > 0) {
                delay(wait)
                wait *= 2
            }
            tries++
            done = attempt(deviceId, payload, plaintext)
        }
        if (!done) logger.warn("Gave up on a {} push for device {} after {} tries.", payload.kind, deviceId, tries)
    }

    /** One try. True when there is nothing left to do: sent, refused for good, or no registration any more. */
    private suspend fun attempt(
        deviceId: String,
        payload: PushPayload,
        plaintext: ByteArray,
    ): Boolean {
        // Read before every try, not when the push was queued, so an unregister or a revoke stops it at once.
        val target = store.push(deviceId) ?: return true
        val approval = payload.kind == PushKind.APPROVAL
        val ttl = if (approval) settings.approvalTtlSeconds else settings.otherTtlSeconds
        val urgency = if (approval) PushUrgency.HIGH else PushUrgency.NORMAL
        val body = encryptor.encrypt(plaintext, target.p256dh, target.auth)
        return when (val outcome = transport.send(target.endpoint, body, ttl, urgency)) {
            SendOutcome.Accepted -> {
                true
            }

            SendOutcome.Gone -> {
                logger.info("The push service no longer knows device {}. Its registration is deleted.", deviceId)
                store.deletePush(deviceId, target.endpoint)
                true
            }

            is SendOutcome.Failed -> {
                logger.warn("A {} push for device {} was refused: {}", payload.kind, deviceId, outcome.reason)
                true
            }

            is SendOutcome.Retry -> {
                logger.debug("A {} push for device {} is tried again: {}", payload.kind, deviceId, outcome.reason)
                false
            }
        }
    }

    private fun PushDecision.Send.payload() = PushPayload(kind = kind, sessionId = sessionId, approvalId = approvalId)

    private companion object {
        val logger = LoggerFactory.getLogger(PushNotifier::class.java)
    }
}
