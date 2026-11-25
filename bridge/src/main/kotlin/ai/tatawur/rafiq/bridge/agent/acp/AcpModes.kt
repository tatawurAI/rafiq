package ai.tatawur.rafiq.bridge.agent.acp

import ai.tatawur.rafiq.bridge.agent.AgentModes
import ai.tatawur.rafiq.protocol.SessionMode
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.rpc.MethodName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The permission modes of one ACP session, as the agent advertises them.
 *
 * ACP has two ways to offer modes, and an agent may use either or both:
 * - session modes: `modes` in the answer to `session/new`, `session/load` or `session/resume`, changed with
 *   `session/set_mode` and reported with `current_mode_update`;
 * - a session config option of category `mode` and type `select`, changed with `session/set_config_option` and
 *   reported with `config_option_update`.
 *
 * Session modes win when both are offered, because they are the older and more widely implemented of the two.
 * Nothing here names a particular agent's modes: the app shows whatever the agent advertised.
 *
 * Thread-safe: the answers are read on the caller's coroutine, the updates on the session's consumer.
 */
internal class AcpModes {
    private val state = MutableStateFlow(AgentModes.NONE)

    @Volatile private var control: Control? = null

    /** The modes as last advertised or reported. */
    val modes: StateFlow<AgentModes> = state.asStateFlow()

    /** How the session's mode is changed. */
    private sealed interface Control {
        data object SessionModes : Control

        data class ConfigOption(
            val configId: String,
        ) : Control
    }

    /** Reads the modes from the answer to `session/new`, `session/load` or `session/resume`. */
    fun advertised(answer: JsonElement?) {
        val result = answer as? JsonObject ?: return
        val modes = result["modes"] as? JsonObject
        val sessionModes = modes?.let(::fromSessionModes)?.takeIf { it.available.isNotEmpty() }
        val option = if (sessionModes == null) modeOption(result["configOptions"]) else null
        when {
            sessionModes != null -> {
                control = Control.SessionModes
                state.value = sessionModes
            }

            option != null -> {
                control = Control.ConfigOption(option.first)
                state.value = option.second
            }
        }
    }

    /**
     * Applies a root session `session/update`, which may report a mode change. Updates on helper sessions are
     * not passed here: a helper's mode is not the session's.
     */
    fun update(update: JsonObject) {
        when (update.string("sessionUpdate")) {
            "current_mode_update" -> {
                val modeId = update.string("currentModeId") ?: return
                if (control == Control.SessionModes) state.value = state.value.copy(currentModeId = modeId)
            }

            "config_option_update" -> {
                applyConfigOptions(update["configOptions"])
            }
        }
    }

    /**
     * The request that switches the root session [acpSessionId] to [modeId].
     *
     * @throws IllegalArgumentException when the agent does not offer [modeId].
     */
    fun request(
        acpSessionId: String,
        modeId: String,
    ): Pair<MethodName, JsonObject> {
        val how = control
        require(how != null && state.value.offers(modeId)) { "The agent does not offer the mode '$modeId'." }
        val session = "sessionId" to JsonPrimitive(acpSessionId)
        return when (how) {
            Control.SessionModes -> {
                AcpMethod.AgentMethods.V1.SessionSetMode.methodName to
                    JsonObject(mapOf(session, "modeId" to JsonPrimitive(modeId)))
            }

            is Control.ConfigOption -> {
                AcpMethod.AgentMethods.V1.SessionSetConfigOption.methodName to
                    JsonObject(
                        mapOf(
                            session,
                            "configId" to JsonPrimitive(how.configId),
                            "value" to JsonPrimitive(modeId),
                        ),
                    )
            }
        }
    }

    /** The agent accepted [modeId] with [answer]. A config option answer carries the full new state. */
    fun accepted(
        modeId: String,
        answer: JsonElement?,
    ) {
        state.value = state.value.copy(currentModeId = modeId)
        if (control is Control.ConfigOption) applyConfigOptions((answer as? JsonObject)?.get("configOptions"))
    }

    /**
     * A config option update is the full list of options. The mode option's current value always counts. Its
     * choices replace the advertised ones only when modes come from that option.
     */
    private fun applyConfigOptions(raw: JsonElement?) {
        val (configId, modes) = modeOption(raw) ?: return
        when (val how = control) {
            is Control.ConfigOption -> {
                if (how.configId == configId) state.value = modes
            }

            Control.SessionModes -> {
                state.value = state.value.copy(currentModeId = modes.currentModeId)
            }

            null -> {
                Unit
            }
        }
    }
}

/** `{currentModeId, availableModes: [{id, name, description}]}`. */
private fun fromSessionModes(raw: JsonObject): AgentModes {
    val available =
        (raw["availableModes"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val mode = entry as? JsonObject ?: return@mapNotNull null
            val id = mode.string("id") ?: return@mapNotNull null
            SessionMode(id = id, name = mode.string("name") ?: id, description = mode.string("description"))
        }
    return AgentModes(currentModeId = raw.string("currentModeId"), available = available)
}

/**
 * The first config option of category `mode` and type `select`, as its id and modes. Its options may be flat,
 * `[{value, name, description}]`, or grouped, `[{group, name, options: [...]}]`.
 */
private fun modeOption(raw: JsonElement?): Pair<String, AgentModes>? {
    val option =
        (raw as? JsonArray).orEmpty().firstNotNullOfOrNull { entry ->
            (entry as? JsonObject)?.takeIf {
                it.string("category") == "mode" && it.string("type") == "select" && it.string("id") != null
            }
        } ?: return null
    val id = option.string("id").orEmpty()
    val choices =
        (option["options"] as? JsonArray).orEmpty().flatMap { entry ->
            val choice = entry as? JsonObject
            val grouped = choice?.get("options") as? JsonArray
            grouped?.toList() ?: listOfNotNull(choice)
        }
    val available =
        choices.mapNotNull { entry ->
            val choice = entry as? JsonObject ?: return@mapNotNull null
            val value = choice.string("value") ?: return@mapNotNull null
            SessionMode(id = value, name = choice.string("name") ?: value, description = choice.string("description"))
        }
    return id to AgentModes(currentModeId = option.string("currentValue"), available = available)
}
