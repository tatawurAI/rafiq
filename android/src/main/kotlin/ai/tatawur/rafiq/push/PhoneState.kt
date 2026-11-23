package ai.tatawur.rafiq.push

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// The phone's side of the Notifications settings: what Android says, and the system screens Settings opens.

/** What the phone says about notifications, battery and push apps, read once per resume. */
internal data class PhoneState(
    val notificationsAllowed: Boolean,
    val rafiqExempt: Boolean,
    val distributorPackage: String?,
    val distributorLabel: String?,
    val installed: List<Pair<String, String>>,
) {
    companion object {
        fun read(
            context: Context,
            distributor: UnifiedPushDistributor,
        ): PhoneState {
            val current = distributor.current()
            return PhoneState(
                notificationsAllowed =
                    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true,
                rafiqExempt =
                    context
                        .getSystemService(
                            PowerManager::class.java,
                        )?.isIgnoringBatteryOptimizations(context.packageName) ==
                        true,
                distributorPackage = current,
                distributorLabel = current?.let(distributor::label),
                installed = distributor.installed().map { it to distributor.label(it) },
            )
        }
    }
}

internal fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without that settings page or a browser. There is nothing better to open.
    }
}

// The exemption is for Rafiq itself, so it can reach your server when a push wakes it. Declared in the manifest.
@SuppressLint("BatteryLife")
internal fun Context.requestBatteryExemption() {
    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
}

/** Opens Android's notification settings for Rafiq. */
internal fun Context.openNotificationSettings() {
    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}
