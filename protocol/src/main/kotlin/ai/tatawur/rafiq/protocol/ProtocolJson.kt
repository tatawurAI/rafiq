package ai.tatawur.rafiq.protocol

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration both the bridge and the app use for protocol messages.
 *
 * Its settings are what keep an older peer working against a newer one:
 * - `ignoreUnknownKeys`: a field added later is skipped, not an error.
 * - `coerceInputValues`: an enum value added later decodes to that property's `UNKNOWN` default.
 * - `classDiscriminator = "type"`: the key that names a payload or content block kind.
 * - `encodeDefaults`: every field is written, so other clients see the full shape.
 * - `explicitNulls = false`: absent optional values are left out rather than written as `null`.
 */
val protocolJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
    }
