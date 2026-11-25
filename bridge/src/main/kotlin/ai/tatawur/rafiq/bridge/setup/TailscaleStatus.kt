package ai.tatawur.rafiq.bridge.setup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The parts of `tailscale status --json` that `setup` uses. Tailscale documents this output as unstable, so only two
 * fields are read and anything unexpected gives an empty status instead of an error.
 *
 * @property running true when Tailscale reports the `Running` state, so this machine is signed in and connected.
 * @property dnsName this machine's MagicDNS name without the final dot, for example `box.example.ts.net`, or null
 *   when MagicDNS is off or the output did not have it.
 */
data class TailscaleStatus(
    val running: Boolean,
    val dnsName: String?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads [output], the text `tailscale status --json` printed. */
        fun parse(output: String): TailscaleStatus {
            val root =
                try {
                    json.parseToJsonElement(output) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return TailscaleStatus(running = false, dnsName = null)
            val state = (root["BackendState"] as? JsonPrimitive)?.contentOrNull
            val name =
                ((root["Self"] as? JsonObject)?.get("DNSName") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.lowercase()
                    ?.takeIf { HOST_NAME.matches(it) }
            return TailscaleStatus(running = state == "Running", dnsName = name)
        }

        // A DNS name of letters, digits, hyphens and dots, so nothing else ends up in the settings file.
        private val HOST_NAME = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
    }
}
