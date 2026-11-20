package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry in a session's append-only event log, as the bridge sends it to your phone.
 *
 * The envelope carries what replay needs. The bridge stores events in [seq] order and, on
 * reconnect, sends every event after the last [seq] the app has. The [payload] says what happened.
 *
 * @property sessionId the session this event belongs to.
 * @property seq position in the session's log. Starts at 1 and increases by one per event, per session.
 * @property timestampMs when the bridge recorded the event, in milliseconds since the Unix epoch (UTC).
 *   Epoch milliseconds rather than an ISO-8601 string because both ends compare and sort timestamps,
 *   and a number needs no parsing, has no time-zone or precision variants, and stays compact over a
 *   slow link. Formatting for display happens in the app.
 * @property payload what happened. A payload this build cannot decode becomes [EventPayload.Unknown].
 */
@Serializable
data class Event(
    @SerialName("session_id") val sessionId: String,
    @SerialName("seq") val seq: Long,
    @SerialName("ts_ms") val timestampMs: Long,
    @SerialName("payload") val payload: EventPayload,
)
