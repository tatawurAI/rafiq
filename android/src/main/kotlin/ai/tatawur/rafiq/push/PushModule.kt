package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.client.push.DistributorProblem
import ai.tatawur.rafiq.client.push.PushNotices
import ai.tatawur.rafiq.client.push.PushRegistrar
import ai.tatawur.rafiq.client.sync.AppConditions
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.sync.BridgeCredentials
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Push for the whole app (ADR 0010): the distributor, your server's copy of the endpoint, and the
 * notifications. Built once by the app container.
 *
 * @param api the bridge routes, with the paired credentials.
 * @param scope outlives any one screen, so a push handled in the background finishes.
 */
class PushModule(
    context: Context,
    val api: BridgeApi,
    credentials: StateFlow<BridgeCredentials?>,
    private val conditions: AppConditions,
    val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    @Volatile private var shownSession: String? = null
    private val pendingOpen = MutableStateFlow<String?>(null)

    internal val notifier = PushNotifier(appContext)

    internal val distributor = UnifiedPushDistributor(appContext) { problem -> registrar.distributorProblem(problem) }

    /** Your server's copy of this phone's endpoint, and where push stands for Settings. */
    val registrar: PushRegistrar = PushRegistrar(api, distributor, credentials, conditions.networkAvailable, scope)

    private val notices = PushNotices(api, isShowing = { id -> conditions.foreground.value && shownSession == id })

    /**
     * A session a notification asked to open, until the navigation [opened] it. Held rather than emitted, so a
     * tap that cold-starts the app is not lost before the screens exist.
     */
    val openRequest: StateFlow<String?> = pendingOpen.asStateFlow()

    init {
        PushChannels.declare(appContext)
        registrar.start()
    }

    /** A push arrived. Decides what to show, fetching from your server, and shows it. */
    fun received(
        content: ByteArray,
        decrypted: Boolean,
    ) {
        scope.launch { notices.handle(content, decrypted, notifier::show) }
    }

    /** The distributor dropped this phone's endpoint. */
    fun endpointLost() {
        scope.launch {
            registrar.endpointLost()
            if (distributor.installed().isEmpty()) registrar.distributorProblem(DistributorProblem.NONE_INSTALLED)
        }
    }

    /**
     * The session on screen, or null when none is. A push for it while the app is in the foreground shows no
     * notification, and opening it clears its finished and error notifications.
     */
    fun sessionShown(sessionId: String?) {
        shownSession = sessionId
        if (sessionId != null) notifier.sessionOpened(sessionId)
    }

    /** A notification asked to open [sessionId]. */
    fun open(sessionId: String) {
        pendingOpen.value = sessionId
    }

    /** The navigation opened [sessionId]. */
    fun opened(sessionId: String) {
        pendingOpen.compareAndSet(sessionId, null)
    }

    /** You picked [packageName] in Settings as the push app. */
    fun choose(packageName: String) {
        distributor.choose(packageName)
    }
}
