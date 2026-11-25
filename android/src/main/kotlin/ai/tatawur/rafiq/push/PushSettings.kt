package ai.tatawur.rafiq.push

import ai.tatawur.rafiq.R
import ai.tatawur.rafiq.client.push.DistributorProblem
import ai.tatawur.rafiq.client.push.PushStatus
import ai.tatawur.rafiq.ui.theme.RafiqTheme
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Where to get ntfy, the distributor the docs describe (ADR 0010). */
private const val NTFY_URL = "https://docs.ntfy.sh/subscribe/phone/"

/**
 * The Notifications part of Settings, wired to [push]: whether notifications are allowed, which push app
 * delivers them and how it stands, and the battery settings that keep pushes on time. It reads the phone's
 * state again each time Settings comes back to the front, so returning from a system screen shows the change.
 */
@Composable
fun PushSettingsRoute(push: PushModule) {
    val context = LocalContext.current
    val status by push.registrar.status.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val phone = remember(resumed, status) { PhoneState.read(context, push.distributor) }
    var choosing by remember { mutableStateOf(false) }
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Asked before and refused: Android no longer shows the question, so open the app's page instead.
            if (!granted) context.openNotificationSettings()
            resumed++
        }
    PushSettingsSection(
        status = status,
        distributor = phone.distributorLabel,
        notificationsAllowed = phone.notificationsAllowed,
        rafiqExempt = phone.rafiqExempt,
        onAllowNotifications = {
            // Before Android 13 there is no question to ask: notifications are on unless you turned them off.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.openNotificationSettings()
            }
        },
        onGetNtfy = { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(NTFY_URL))) },
        onSetUp = push.registrar::retry,
        onChoose = { choosing = true },
        onRafiqBattery = { context.requestBatteryExemption() },
        onDistributorBattery = {
            phone.distributorPackage?.let { pkg ->
                context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }
        },
    )
    if (choosing) {
        DistributorChoice(
            choices = phone.installed,
            onChoose = { pkg ->
                choosing = false
                push.choose(pkg)
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * The Notifications section. Stateless, so its states can be previewed.
 *
 * @param distributor the push app's name, such as "ntfy", or null when none is chosen.
 * @param rafiqExempt true when Rafiq may use the network in the background without battery limits.
 */
@Suppress("LongParameterList", "LongMethod") // One section, one callback per button it may show.
@Composable
fun PushSettingsSection(
    status: PushStatus,
    distributor: String?,
    notificationsAllowed: Boolean,
    rafiqExempt: Boolean,
    onAllowNotifications: () -> Unit,
    onGetNtfy: () -> Unit,
    onSetUp: () -> Unit,
    onChoose: () -> Unit,
    onRafiqBattery: () -> Unit,
    onDistributorBattery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RafiqTheme.spacing
    val colors = RafiqTheme.colors
    val name = distributor ?: stringResource(R.string.push_distributor_unknown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            stringResource(R.string.push_settings_title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
        )
        Detail(statusText(status, distributor, name))
        if (!notificationsAllowed && status != PushStatus.NotPaired) {
            Detail(stringResource(R.string.push_notifications_blocked))
            Action(R.string.push_allow_notifications, onAllowNotifications)
        }
        when (status) {
            PushStatus.NoDistributor -> {
                Action(R.string.push_get_ntfy, onGetNtfy)
                Action(R.string.push_set_up, onSetUp)
            }

            PushStatus.ChooseDistributor -> {
                Action(R.string.push_choose, onChoose)
            }

            is PushStatus.DistributorFailed, is PushStatus.Refused -> {
                Action(R.string.push_set_up, onSetUp)
            }

            PushStatus.NotPaired, PushStatus.Waiting, PushStatus.Ready, PushStatus.Offline -> {
                Unit
            }
        }
        if (status != PushStatus.NotPaired && status != PushStatus.NoDistributor) {
            if (!rafiqExempt) {
                Text(
                    stringResource(R.string.push_battery_rafiq),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
                Detail(stringResource(R.string.push_battery_rafiq_detail))
                Action(R.string.push_battery_open, onRafiqBattery)
            }
            if (distributor != null) {
                Text(
                    stringResource(R.string.push_battery_distributor, distributor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
                Detail(stringResource(R.string.push_battery_distributor_detail, distributor))
                Action(R.string.push_battery_open, onDistributorBattery)
            }
        }
    }
}

@Composable
private fun statusText(
    status: PushStatus,
    distributor: String?,
    name: String,
): String =
    when (status) {
        PushStatus.NotPaired -> {
            stringResource(R.string.push_status_not_paired)
        }

        PushStatus.NoDistributor -> {
            stringResource(R.string.push_status_no_distributor)
        }

        PushStatus.ChooseDistributor -> {
            stringResource(R.string.push_status_choose)
        }

        PushStatus.Waiting -> {
            distributor?.let { stringResource(R.string.push_status_waiting, it) }
                ?: stringResource(R.string.push_status_waiting_unknown)
        }

        PushStatus.Ready -> {
            stringResource(R.string.push_status_ready, name)
        }

        PushStatus.Offline -> {
            stringResource(R.string.push_status_offline)
        }

        is PushStatus.Refused -> {
            stringResource(R.string.push_status_refused, status.message)
        }

        is PushStatus.DistributorFailed -> {
            distributorFailedText(status.reason, name)
        }
    }

@Composable
private fun distributorFailedText(
    reason: DistributorProblem,
    name: String,
): String =
    when (reason) {
        DistributorProblem.ACTION_REQUIRED -> stringResource(R.string.push_status_action_required, name)
        DistributorProblem.VAPID_REQUIRED -> stringResource(R.string.push_status_vapid, name)
        DistributorProblem.NONE_INSTALLED -> stringResource(R.string.push_status_no_distributor)
        DistributorProblem.NOT_CHOSEN -> stringResource(R.string.push_status_choose)
        DistributorProblem.OTHER -> stringResource(R.string.push_status_failed, name)
    }

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = RafiqTheme.colors.textSecondary)
}

@Composable
private fun Action(
    label: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = RafiqTheme.spacing.minTouchTarget)) {
        Text(stringResource(label))
    }
}

/** Picks one of several installed push apps. */
@Composable
private fun DistributorChoice(
    choices: List<Pair<String, String>>,
    onChoose: (packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.push_choose)) },
        text = {
            Column {
                choices.forEach { (pkg, label) ->
                    TextButton(
                        onClick = { onChoose(pkg) },
                        modifier = Modifier.heightIn(min = RafiqTheme.spacing.minTouchTarget),
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_unlock_cancel)) } },
    )
}

@Preview(name = "Notifications, no push app", widthDp = 412, heightDp = 400)
@Composable
private fun PushNoDistributorPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PushSettingsSection(PushStatus.NoDistributor, null, true, false, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(name = "Notifications, ready, blocked", widthDp = 412, heightDp = 600)
@Composable
private fun PushReadyPreview() {
    RafiqTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            PushSettingsSection(PushStatus.Ready, "ntfy", false, false, {}, {}, {}, {}, {}, {})
        }
    }
}
