package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import ai.tatawur.rafiq.client.Reduction
import ai.tatawur.rafiq.client.SessionListItem
import ai.tatawur.rafiq.client.SessionReducer
import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.client.SessionScreenState
import ai.tatawur.rafiq.client.toListItems
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The app's [SessionRepository]: the cache first, then the bridge. Everything here is plain Kotlin, so
 * the reconnect and replay rules run in JVM tests (ADR 0011). :android supplies the ports.
 *
 * - [session] folds the cached events, then holds a socket open while the app is in the foreground. Each
 *   event is cached before it is applied, and every reconnect resumes after the newest applied `seq`
 *   (ADR 0005). A gap in `seq` reconnects at once and replays what was missed.
 * - [sessions] shows the cached list, then polls `GET /sessions/summaries` while the app is in the
 *   foreground.
 *
 * @param scope lives as long as the app. It only keeps [connection] up to date.
 * @param nowMs the wall clock in milliseconds since the Unix epoch, for [ConnectionState.Offline.retryAtMs].
 * @param onApplied called with each event once it is cached and applied, for debug logging.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Separate ports keep test fakes plain; one socket loop's steps.
class SyncingSessionRepository(
    http: HttpTransport,
    private val source: EventSource,
    private val store: EventStore,
    private val conditions: AppConditions,
    private val credentials: StateFlow<BridgeCredentials?>,
    scope: CoroutineScope,
    private val nowMs: () -> Long,
    random: Random = Random.Default,
    private val pollInterval: Duration = 5.seconds,
    private val onApplied: (Event) -> Unit = {},
) : SessionRepository {
    private val api = BridgeApi(http) { credentials.value }
    private val backoff = Backoff(random)
    private val link = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    // While a socket is open or retrying it speaks for the link, and the list poll stays quiet.
    private val openSockets = AtomicInteger(0)

    // The first value is worked out here, not by the coroutine that keeps it current: the first screen
    // reads it before that coroutine runs, and a paired app must never look unpaired, even for a frame.
    override val connection: StateFlow<ConnectionState> =
        combine(credentials, link, ::connectionFor)
            .stateIn(scope, SharingStarted.Eagerly, connectionFor(credentials.value, link.value))

    override fun sessions(): Flow<List<SessionListItem>> =
        channelFlow {
            send(store.summaries().toListItems())
            whileAllowed { _ ->
                while (true) {
                    pollOnce()?.let { send(it) }
                    delay(pollInterval)
                }
            }
        }

    override fun session(sessionId: String): Flow<SessionScreenState> =
        channelFlow {
            var state = SessionReducer.fold(sessionId, store.events(sessionId))
            send(state)
            whileAllowed { paired ->
                openSockets.incrementAndGet()
                try {
                    stream(paired, { state }) { next ->
                        state = next
                        send(next)
                    }
                } finally {
                    openSockets.decrementAndGet()
                }
            }
        }

    override suspend fun agents(): CommandResult<List<String>> = api.agents().noting()

    override suspend fun createSession(
        agent: String,
        projectPath: String,
        title: String?,
    ): CommandResult<Session> = api.createSession(agent, projectPath, title).noting()

    override suspend fun prompt(
        sessionId: String,
        text: String,
    ): CommandResult<Unit> = api.prompt(sessionId, text).noting()

    override suspend fun cancel(
        sessionId: String,
        threadId: String,
    ): CommandResult<Unit> = api.cancel(sessionId, threadId).noting()

    override suspend fun approve(
        approvalId: String,
        optionId: String,
    ): CommandResult<Unit> = api.approve(approvalId, optionId).noting()

    /** Runs [block] while the app is in the foreground and paired, restarting it when the pairing changes. */
    private suspend fun whileAllowed(block: suspend (BridgeCredentials) -> Unit) {
        combine(conditions.foreground, credentials) { foreground, paired -> paired.takeIf { foreground } }
            .distinctUntilChanged()
            .collectLatest { paired -> if (paired != null) block(paired) }
    }

    private suspend fun pollOnce(): List<SessionListItem>? {
        val result = api.summaries()
        if (openSockets.get() == 0) {
            when (result) {
                is CommandResult.Ok -> {
                    link.value = ConnectionState.Live
                }

                CommandResult.Offline -> {
                    link.value =
                        ConnectionState.Offline(nowMs() + pollInterval.inWholeMilliseconds)
                }

                else -> {
                    result.noting()
                }
            }
        }
        if (result !is CommandResult.Ok) return null
        store.replaceSummaries(result.value)
        return result.value.toListItems()
    }

    /** Keeps one session's socket going until cancelled or the token is refused. */
    private suspend fun stream(
        paired: BridgeCredentials,
        current: () -> SessionScreenState,
        commit: suspend (SessionScreenState) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            link.value = ConnectionState.Connecting
            val result = connectOnce(paired, current, commit)
            if (result.progressed) attempt = 0
            if (result.end == StreamEnd.REFUSED) {
                link.value = ConnectionState.Unauthorized
                return
            }
            // The socket was making progress, so the missing events are a replay away.
            if (result.end == StreamEnd.GAP && result.progressed) continue
            waitToRetry(backoff.delayFor(attempt))
            attempt++
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever breaks the socket, the answer is to reconnect.
    private suspend fun connectOnce(
        paired: BridgeCredentials,
        current: () -> SessionScreenState,
        commit: suspend (SessionScreenState) -> Unit,
    ): ConnectResult {
        var progressed = false
        val state = current()
        val end =
            try {
                source.open(eventsUrl(paired.baseUrl, state.sessionId, state.lastSeq), paired.token).collect { frame ->
                    when (frame) {
                        SocketFrame.Opened -> link.value = ConnectionState.Live
                        is SocketFrame.Text -> if (applyFrame(frame.text, current(), commit)) progressed = true
                    }
                }
                StreamEnd.DROPPED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: StreamBroken) {
                broken.end
            } catch (rejected: HandshakeRejectedException) {
                if (rejected.status == UNAUTHORIZED) StreamEnd.REFUSED else StreamEnd.DROPPED
            } catch (_: Exception) {
                StreamEnd.DROPPED
            }
        return ConnectResult(end, progressed)
    }

    /** Caches and applies one frame. Returns true when it was the next event. */
    private suspend fun applyFrame(
        text: String,
        state: SessionScreenState,
        commit: suspend (SessionScreenState) -> Unit,
    ): Boolean {
        val event =
            try {
                protocolJson.decodeFromString(Event.serializer(), text)
            } catch (_: IllegalArgumentException) {
                // Not even an envelope: its seq is unknown, so the log cannot continue past it on this socket.
                throw StreamBroken(StreamEnd.DROPPED)
            }
        return when (val reduction = SessionReducer.reduce(state, event)) {
            is Reduction.Applied -> {
                store.append(event)
                commit(reduction.state)
                onApplied(event)
                true
            }

            is Reduction.Ignored -> {
                false
            }

            is Reduction.Gap -> {
                throw StreamBroken(StreamEnd.GAP)
            }
        }
    }

    /** Waits out [wait], or until the network comes back, whichever is first. */
    private suspend fun waitToRetry(wait: Duration) {
        val network = conditions.networkAvailable
        if (!network.value) {
            link.value = ConnectionState.Offline(retryAtMs = null)
            network.first { it }
            return
        }
        link.value = ConnectionState.Offline(nowMs() + wait.inWholeMilliseconds)
        withTimeoutOrNull(wait) {
            network.first { !it }
            network.first { it }
        }
    }

    private fun connectionFor(
        paired: BridgeCredentials?,
        state: ConnectionState,
    ): ConnectionState = if (paired == null) ConnectionState.NotPaired else state

    private fun <T> CommandResult<T>.noting(): CommandResult<T> =
        also {
            if (it == CommandResult.Unauthorized &&
                credentials.value != null
            ) {
                link.value = ConnectionState.Unauthorized
            }
        }

    private enum class StreamEnd { DROPPED, GAP, REFUSED }

    private data class ConnectResult(
        val end: StreamEnd,
        val progressed: Boolean,
    )

    private class StreamBroken(
        val end: StreamEnd,
    ) : Exception()

    private companion object {
        const val UNAUTHORIZED = 401
    }
}

/** `wss://…/events?session=<id>&after=<seq>` for the bridge at [baseUrl]. */
internal fun eventsUrl(
    baseUrl: String,
    sessionId: String,
    afterSeq: Long,
): String = baseUrl.replaceFirst("https://", "wss://") + "/events?session=${segment(sessionId)}&after=$afterSeq"
