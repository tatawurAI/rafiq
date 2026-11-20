package ai.tatawur.rafiq.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Serializer for a closed set of kinds that must still accept kinds it has never seen.
 *
 * A newer bridge can send a payload or content block type that this build does not know, or a
 * known type whose fields this build cannot read. The generated sealed serializer would throw on
 * either and lose the whole [Event]. This one decodes known types with [knownSerializer] and wraps
 * anything it cannot decode in the caller's unknown case. That case keeps the raw JSON object, so
 * the value can be shown as plain text, stored and re-encoded unchanged.
 *
 * It works only with JSON, which is the only format the protocol uses.
 *
 * @param T the base type, which includes the unknown case.
 * @param K the sealed subtype that holds every known kind.
 */
internal abstract class FallbackSerializer<T : Any, K : T>(
    serialName: String,
    private val knownSerializer: KSerializer<K>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    // A sealed serializer's descriptor has a "type" element and a "value" element whose
    // children are named after each subclass's @SerialName.
    private val knownTypes: Set<String> by lazy {
        knownSerializer.descriptor
            .getElementDescriptor(1)
            .elementNames
            .toSet()
    }

    /** Returns [value] as a known kind, or null when it is the unknown case. */
    protected abstract fun asKnown(value: T): K?

    /** Builds the unknown case around the raw object, exactly as received. */
    protected abstract fun unknown(raw: JsonObject): T

    /** Returns the raw object held by the unknown case. */
    protected abstract fun rawOf(value: T): JsonObject

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: throw SerializationException("${descriptor.serialName} is JSON only.")
        val discriminator = input.json.configuration.classDiscriminator
        val raw = input.decodeJsonElement().jsonObject
        val type = (raw[discriminator] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type !in knownTypes) return unknown(raw)
        return try {
            input.json.decodeFromJsonElement(knownSerializer, raw)
        } catch (_: SerializationException) {
            // A known type with a missing or malformed field, typically from a peer on a
            // different protocol version. Losing this one payload beats losing the event.
            unknown(raw)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("${descriptor.serialName} is JSON only.")
        val known = asKnown(value)
        if (known != null) {
            output.encodeSerializableValue(knownSerializer, known)
        } else {
            output.encodeJsonElement(rawOf(value))
        }
    }
}
