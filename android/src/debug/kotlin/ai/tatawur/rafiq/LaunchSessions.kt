package ai.tatawur.rafiq

import ai.tatawur.rafiq.client.SessionRepository
import ai.tatawur.rafiq.ui.sample.sampleSessionRepository
import android.content.Intent

/**
 * The repository the screens use for this launch. Debug builds start on made-up sessions when the
 * launch carries the boolean extra [SAMPLE_EXTRA], so the screens can be reviewed and captured
 * without a bridge:
 *
 * `adb shell am start -n ai.tatawur.rafiq/.MainActivity --ez sample true`
 *
 * Release builds have their own copy of this function, which always returns [paired].
 */
internal fun sessionsForLaunch(
    intent: Intent,
    paired: SessionRepository,
): SessionRepository = if (intent.getBooleanExtra(SAMPLE_EXTRA, false)) sampleSessionRepository else paired

private const val SAMPLE_EXTRA = "sample"
