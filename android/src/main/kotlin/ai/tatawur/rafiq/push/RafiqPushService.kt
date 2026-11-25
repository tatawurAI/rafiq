package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.client.push.DistributorProblem
import ai.tatawur.rafiq.data.RafiqApplication
import ai.tatawur.rafiq.protocol.api.PushRegistration
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Where the UnifiedPush connector delivers endpoints and pushes. It only hands them to [PushModule]; the work
 * runs in the module's scope, because the connector unbinds this service five seconds after an event.
 */
class RafiqPushService : PushService() {
    private val push: PushModule
        get() = (application as RafiqApplication).container.push

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        // Without the phone's public keys your server could only send in the clear, which Rafiq never does.
        val keys = endpoint.pubKeySet ?: return push.registrar.distributorProblem(DistributorProblem.OTHER)
        val module = push
        module.scope.launch { module.registrar.endpoint(PushRegistration(endpoint.url, keys.pubKey, keys.auth)) }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        push.received(message.content, message.decrypted)
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        push.registrar.distributorProblem(
            when (reason) {
                FailedReason.ACTION_REQUIRED -> DistributorProblem.ACTION_REQUIRED
                FailedReason.VAPID_REQUIRED -> DistributorProblem.VAPID_REQUIRED
                FailedReason.NETWORK, FailedReason.INTERNAL_ERROR -> DistributorProblem.OTHER
            },
        )
    }

    override fun onUnregistered(instance: String) {
        push.endpointLost()
    }
}
