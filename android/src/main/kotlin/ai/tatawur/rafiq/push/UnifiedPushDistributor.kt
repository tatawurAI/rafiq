package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.client.push.DistributorProblem
import ai.tatawur.rafiq.client.push.PushDistributor
import android.content.Context
import android.content.pm.PackageManager
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * The phone's UnifiedPush distributor, through the connector library. The connector makes and keeps the Web
 * Push key pair and decrypts pushes with it (RFC 8291), so the app never handles the private key.
 *
 * @param problem reports why no endpoint can be asked for, such as no distributor installed.
 */
internal class UnifiedPushDistributor(
    private val context: Context,
    private val problem: (DistributorProblem) -> Unit,
) : PushDistributor {
    override fun subscribe() {
        val chosen = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        if (chosen != null && chosen in UnifiedPush.getDistributors(context)) {
            register()
            return
        }
        when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> {
                UnifiedPush.saveDistributor(context, resolved.packageName)
                register()
            }

            ResolvedDistributor.ToSelect -> {
                problem(DistributorProblem.NOT_CHOSEN)
            }

            ResolvedDistributor.NoneAvailable -> {
                problem(DistributorProblem.NONE_INSTALLED)
            }
        }
    }

    override fun unsubscribe() {
        UnifiedPush.unregister(context)
    }

    /** Uses [packageName] from now on, after you picked it in Settings. */
    fun choose(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        register()
    }

    /** The installed distributors, as package names. */
    fun installed(): List<String> = UnifiedPush.getDistributors(context)

    /** The distributor in use, or null when none is chosen. */
    fun current(): String? = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)

    /** What Android calls [packageName], such as "ntfy", for Settings to name it. */
    fun label(packageName: String): String =
        try {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

    private fun register() {
        // No VAPID key: the bridge signs nothing, and ntfy needs none (ADR 0010).
        UnifiedPush.register(context, messageForDistributor = MESSAGE_FOR_DISTRIBUTOR)
    }

    private companion object {
        // How the distributor's own app lists this registration.
        const val MESSAGE_FOR_DISTRIBUTOR = "Rafiq"
    }
}
