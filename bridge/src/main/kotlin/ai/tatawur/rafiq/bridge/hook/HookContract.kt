package ai.tatawur.rafiq.bridge.hook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The hook wire format (ADR 0013, issue #48).
 *
 * The hook program connects to the Unix socket at `$RAFIQ_DATA_DIR/hook/hook.sock`. The `hook` directory has
 * mode 0700, and the credential file `$RAFIQ_DATA_DIR/hook/credential` has mode 0600 and is new at every start.
 * Per connection the program writes exactly one [HookRequest] as one line of JSON, ended by `\n`, of at most
 * [MAX_HOOK_REQUEST_BYTES]. For an observer event the bridge closes the connection without a reply. For a
 * [HookKind.PERMISSION] request the bridge holds the connection open and writes at most one [HookReply] line of
 * at most [MAX_HOOK_REPLY_BYTES], then closes. Closing with no reply means "ask at the desk": the program prints
 * nothing and exits 0. The program builds the agent's decision object itself from a validated reply. It never
 * prints the reply as received.
 */

/** The largest request line the bridge reads. A longer one is dropped and the connection closed. */
const val MAX_HOOK_REQUEST_BYTES: Int = 1 shl 20

/** The largest reply line the hook program accepts. A longer one counts as no reply. */
const val MAX_HOOK_REPLY_BYTES: Int = 64 * 1024

/** The version of this wire format, sent as [HookRequest.version]. */
const val HOOK_WIRE_VERSION: Int = 1

/** What a hook request is for. */
@Serializable
enum class HookKind {
    /** An event the bridge only observes. No reply. */
    @SerialName("event")
    EVENT,

    /** A `PermissionRequest` that waits for an answer. */
    @SerialName("permission")
    PERMISSION,
}

/**
 * One hook call, as the hook program sends it.
 *
 * @property credential the contents of the credential file. Never logged.
 * @property agent the agent whose hook ran, for example `claude-code`.
 * @property pane `$TMUX_PANE`, for example `%3`.
 * @property agentPid the agent's process id from the hook environment, when the agent sets one.
 * @property event the agent's hook input JSON, as the agent gave it on stdin.
 * @property tmuxSocket the tmux server's socket path, the first field of `$TMUX`, so the bridge can tell a pane
 *   of its own server from a pane with the same id on another one. Null from an older hook program.
 */
@Serializable
data class HookRequest(
    @SerialName("v") val version: Int,
    @SerialName("credential") val credential: String,
    @SerialName("kind") val kind: HookKind,
    @SerialName("agent") val agent: String,
    @SerialName("pane") val pane: String,
    @SerialName("agent_pid") val agentPid: Long? = null,
    @SerialName("event") val event: JsonObject,
    @SerialName("tmux_socket") val tmuxSocket: String? = null,
)

/** The decision in a [HookReply]. Anything else counts as no reply. */
@Serializable
enum class HookDecision {
    @SerialName("allow")
    ALLOW,

    @SerialName("deny")
    DENY,

    /** Allow, and add [HookReply.rule] for the rest of this agent session only. */
    @SerialName("allow_session")
    ALLOW_SESSION,

    /** Answer a question from the agent with [HookReply.answers]. */
    @SerialName("answers")
    ANSWERS,
}

/**
 * The bridge's answer to a [HookKind.PERMISSION] request.
 *
 * @property message for [HookDecision.DENY], the reason the agent is told.
 * @property rule for [HookDecision.ALLOW_SESSION], the rule to add, in the agent's own rule syntax.
 * @property answers for [HookDecision.ANSWERS], each question's text mapped to the chosen label or labels.
 */
@Serializable
data class HookReply(
    @SerialName("decision") val decision: HookDecision,
    @SerialName("message") val message: String? = null,
    @SerialName("rule") val rule: String? = null,
    @SerialName("answers") val answers: Map<String, String>? = null,
)

/** A hook call that passed the credential check, as the channel hands it to the readers. */
data class HookEnvelope(
    val agent: String,
    val pane: String,
    val agentPid: Long?,
    val event: JsonObject,
    val receivedAtMs: Long,
    val tmuxSocket: String? = null,
)

/**
 * Where the hook channel delivers calls. The channel (issue #48) owns the socket, the credential and the size
 * limits. A reader (issue #42) implements this.
 */
interface HookSink {
    /** An observer event. Must return quickly: the channel does not wait for slow work. */
    suspend fun onEvent(envelope: HookEnvelope)

    /**
     * A permission request. Suspends until it is answered from a phone, settled elsewhere, or times out.
     * Returning null means "ask at the desk". The channel cancels this call the moment the hook program
     * disconnects, so a held request is released by catching the cancellation.
     */
    suspend fun onPermission(envelope: HookEnvelope): HookReply?
}
