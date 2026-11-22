package ai.tatawur.rafiq.protocol.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Text frames of `WS /terminal?session=<id>`. Binary frames carry terminal bytes: down, raw pane output;
 * up, the user's key and paste bytes.
 *
 * Decode with [terminalJson]. A message type this build does not know decodes to [TerminalUnknown].
 */
@Serializable
sealed interface TerminalMessage

/**
 * Down. The whole visible screen, sent on attach and after a resize or a pause. It is followed at once by
 * exactly one binary frame holding the screen as text with colours. The app clears its emulator, writes those
 * bytes, puts the cursor at [cursorX], [cursorY] (zero based) and then applies later binary frames.
 *
 * @property modes the terminal modes the program in the pane has switched on, so paste and arrow keys behave
 * after the screen is restored.
 * @property window the tmux window the pane is in, so the app can show which window is mirrored. Null from a
 * bridge that does not send it.
 */
@Serializable
@SerialName("snapshot")
data class TerminalSnapshot(
    @SerialName("cols") val cols: Int,
    @SerialName("rows") val rows: Int,
    @SerialName("cursor_x") val cursorX: Int,
    @SerialName("cursor_y") val cursorY: Int,
    @SerialName("modes") val modes: TerminalModes = TerminalModes(),
    @SerialName("window") val window: TerminalWindow? = null,
) : TerminalMessage

/** The tmux window a pane is in: its number in the session and its name. */
@Serializable
data class TerminalWindow(
    @SerialName("index") val index: Int,
    @SerialName("name") val name: String,
)

/** Modes of the pane, read from tmux formats. All default to off. */
@Serializable
data class TerminalModes(
    @SerialName("bracketed_paste") val bracketedPaste: Boolean = false,
    @SerialName("app_cursor_keys") val appCursorKeys: Boolean = false,
    @SerialName("mouse") val mouse: Boolean = false,
)

/** Down. The pane's real size changed, for example because the desk took it back. The app resizes its emulator. */
@Serializable
@SerialName("size")
data class TerminalSize(
    @SerialName("cols") val cols: Int,
    @SerialName("rows") val rows: Int,
) : TerminalMessage

/** Down. The pane or window is gone, or the bridge ended the view. The bridge closes the socket after it. */
@Serializable
@SerialName("ended")
data class TerminalEnded(
    @SerialName("reason") val reason: String,
) : TerminalMessage

/** Up. The phone's viewport in character cells. The app sends one right after it opens, then debounced. */
@Serializable
@SerialName("resize")
data class TerminalResize(
    @SerialName("cols") val cols: Int,
    @SerialName("rows") val rows: Int,
) : TerminalMessage

/** A message type this build does not know. Ignore it. */
@Serializable
@SerialName("unknown")
data object TerminalUnknown : TerminalMessage

/** Subprotocol the app lists next to the token on `WS /terminal`, as it does for `WS /events`. */
const val TERMINAL_WS_PROTOCOL = "rafiq.term.v1"

/** JSON for [TerminalMessage]: the `type` field names the message. */
val terminalJson: Json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        serializersModule =
            SerializersModule {
                polymorphic(TerminalMessage::class) {
                    subclass(TerminalUnknown::class)
                    defaultDeserializer { TerminalUnknown.serializer() }
                }
            }
    }
