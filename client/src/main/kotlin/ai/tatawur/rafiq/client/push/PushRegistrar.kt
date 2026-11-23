package ai.tatawur.rafiq.client.push

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.protocol.api.PushRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The UnifiedPush distributor on the phone, such as the ntfy app. `:android` implements it with the
 * UnifiedPush connector, which also makes and keeps the Web Push key pair.
 */
interface PushDistributor {
    /**
     * Asks the distributor for an endpoint. The answer comes back later, through [PushRegistrar.endpoint] or
     * [PushRegistrar.distributorProblem]. Asking again when already subscribed is harmless.
     */
    fun subscribe()

    /** Tells the distributor to drop this phone's endpoint and forgets the keys. */
    fun unsubscribe()
}

/** Where push stands, for the Notifications part of Settings. */
sealed interface PushStatus {
    /** The phone is not paired, so there is nowhere to send pushes from. */
    data object NotPaired : PushStatus

    /** No UnifiedPush distributor is installed. Settings explains how to get ntfy. */
    data object NoDistributor : PushStatus

    /** More than one distributor is installed and none is chosen yet. */
    data object ChooseDistributor : PushStatus

    /** Waiting for the distributor's endpoint, or for your server to take it. */
    data object Waiting : PushStatus

    /** Your server has this phone's endpoint and keys. */
    data object Ready : PushStatus

    /** The distributor refused, for example because it needs you to sign in to it. */
    data class DistributorFailed(
        val reason: DistributorProblem,
    ) : PushStatus

    /** Your server refused the endpoint, for example one that is not `https://`. */
    data class Refused(
        val code: String,
        val message: String,
    ) : PushStatus

    /** Your server could not be reached. The registration is tried again when the network comes back. */
    data object Offline : PushStatus
}

/** Why the distributor did not give an endpoint. */
enum class DistributorProblem {
    /** No distributor is installed, or the chosen one was removed. */
    NONE_INSTALLED,

    /** Several are installed and you have not picked one. */
    NOT_CHOSEN,

    /** The distributor needs you to do something in its own app. */
    ACTION_REQUIRED,

    /** The distributor's push server needs a VAPID key, which Rafiq does not send. */
    VAPID_REQUIRED,

    /** Anything else, including no network. Subscribing again may work. */
    OTHER,
}

/**
 * Keeps your server's copy of this phone's push endpoint in step with the phone (`PUT` and `DELETE
 * /devices/me/push`).
 *
 * - Pairing subscribes to the distributor. Its endpoint is then sent to your server.
 * - A new endpoint, after the distributor changed or renewed it, is sent again.
 * - Pairing with another server, or again with a new token, sends it to that pairing.
 * - Unpairing, or your server revoking the phone, unsubscribes. Your server deletes its copy when it revokes
 *   the device, so there is nothing to send it then.
 * - The distributor dropping the endpoint deletes your server's copy.
 * - A registration that could not reach your server is sent again when the network comes back.
 *
 * Every change runs under one lock, so callbacks from the distributor and pairing changes never interleave.
 */
class PushRegistrar(
    private val api: BridgeApi,
    private val distributor: PushDistributor,
    private val credentials: StateFlow<BridgeCredentials?>,
    private val networkAvailable: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var endpoint: PushRegistration? = null

    // Read without the lock by distributorProblem, which runs on the distributor's callback thread.
    @Volatile private var pairedWith: BridgeCredentials? = null

    // The pairing and endpoint your server last accepted, so the same registration is not sent twice.
    private var sent: Pair<BridgeCredentials, PushRegistration>? = null
    private val state = MutableStateFlow<PushStatus>(PushStatus.NotPaired)

    /** Where push stands now. */
    val status: StateFlow<PushStatus> = state.asStateFlow()

    /** Follows the pairing and the network for as long as [scope] lives. */
    fun start() {
        scope.launch { credentials.collect { paired -> lock.withLock { onPairing(paired) } } }
        scope.launch { networkAvailable.collect { up -> if (up) lock.withLock { sendIfNeeded() } } }
    }

    /** The distributor gave a new endpoint, or the same one again. */
    suspend fun endpoint(registration: PushRegistration) {
        lock.withLock {
            endpoint = registration
            if (pairedWith != null) sendIfNeeded()
        }
    }

    /**
     * The distributor dropped the endpoint: its app was removed, or you removed Rafiq in it. Your server's copy
     * is deleted. The phone does not subscribe again on its own, so removing Rafiq in the distributor sticks
     * until you set push up again in Settings ([retry]).
     */
    suspend fun endpointLost() {
        lock.withLock {
            endpoint = null
            sent = null
            if (pairedWith != null) {
                state.value = PushStatus.DistributorFailed(DistributorProblem.OTHER)
                api.unregisterPush()
            }
        }
    }

    /** The distributor could not give an endpoint, or none can be asked. */
    fun distributorProblem(problem: DistributorProblem) {
        if (pairedWith == null) return
        state.value =
            when (problem) {
                DistributorProblem.NONE_INSTALLED -> PushStatus.NoDistributor
                DistributorProblem.NOT_CHOSEN -> PushStatus.ChooseDistributor
                else -> PushStatus.DistributorFailed(problem)
            }
    }

    /** Asks the distributor again, after you installed or chose one. */
    fun retry() {
        if (pairedWith != null) distributor.subscribe()
    }

    private suspend fun onPairing(paired: BridgeCredentials?) {
        val before = pairedWith
        pairedWith = paired
        when {
            paired == null && before != null -> {
                distributor.unsubscribe()
                endpoint = null
                sent = null
                state.value = PushStatus.NotPaired
            }

            paired == null -> {
                state.value = PushStatus.NotPaired
            }

            else -> {
                if (state.value == PushStatus.NotPaired) state.value = PushStatus.Waiting
                distributor.subscribe()
                sendIfNeeded()
            }
        }
    }

    private suspend fun sendIfNeeded() {
        val paired = pairedWith
        val registration = endpoint
        if (paired == null || registration == null || sent == paired to registration) return
        state.value =
            when (val result = api.registerPush(registration)) {
                is CommandResult.Ok -> {
                    sent = paired to registration
                    PushStatus.Ready
                }

                is CommandResult.Failed -> {
                    PushStatus.Refused(result.code, result.message)
                }

                // A revoked phone is unpaired by the pairing repository, which brings this back to NotPaired.
                else -> {
                    PushStatus.Offline
                }
            }
    }
}
