package ai.tatawur.rafiq

import ai.tatawur.rafiq.client.SessionRepository
import android.content.Intent

/**
 * The repository the screens use for this launch: always the paired one. Debug builds have their
 * own copy of this function that can start on sample data instead, which keeps the sample out of
 * release builds.
 */
@Suppress("UnusedParameter") // Same signature as the debug copy, which reads the intent.
internal fun sessionsForLaunch(
    intent: Intent,
    paired: SessionRepository,
): SessionRepository = paired
