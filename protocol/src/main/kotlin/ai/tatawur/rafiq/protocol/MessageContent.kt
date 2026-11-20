package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Who wrote a message. */
@Serializable
enum class MessageRole {
    /** You, from your phone or another client. */
    @SerialName("user")
    USER,

    @SerialName("agent")
    AGENT,

    /** The bridge itself, for example to report that an agent process exited. */
    @SerialName("system")
    SYSTEM,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * One piece of a message's content. On the wire it is a JSON object whose `type` field names the
 * kind. A kind this build does not know decodes to [Unknown], which the app shows as plain text
 * when it can.
 */
@Serializable(with = ContentBlockSerializer::class)
sealed interface ContentBlock {
    /** The content block kinds this build understands. */
    @Serializable
    sealed interface Known : ContentBlock

    /**
     * A content block this build cannot decode: its type is unknown, absent or not a string, or it is a
     * known type with a missing or malformed field.
     *
     * @property raw the JSON object exactly as received, including its `type` field. It is what
     *   gets re-encoded, so storing and forwarding the content block loses nothing.
     */
    data class Unknown(
        val raw: JsonObject,
    ) : ContentBlock {
        /** The `type` field as received, or null when it was absent or not a string. */
        val type: String?
            get() = (raw["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Plain text, shown as is. */
    @Serializable
    @SerialName("text")
    data class Text(
        @SerialName("text") val text: String,
    ) : Known

    /** CommonMark text, rendered with syntax highlighting for code blocks. */
    @Serializable
    @SerialName("markdown")
    data class Markdown(
        @SerialName("markdown") val markdown: String,
    ) : Known

    /**
     * An image. [uri] is either a `data:` URI or a bridge URL the app fetches with its device token.
     *
     * @property altText a description for screen readers, when the agent gave one.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        @SerialName("uri") val uri: String,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("alt_text") val altText: String? = null,
    ) : Known
}

internal object ContentBlockSerializer :
    FallbackSerializer<ContentBlock, ContentBlock.Known>(
        serialName = "ai.tatawur.rafiq.protocol.ContentBlock",
        knownSerializer = ContentBlock.Known.serializer(),
    ) {
    override fun asKnown(value: ContentBlock): ContentBlock.Known? = value as? ContentBlock.Known

    override fun unknown(raw: JsonObject): ContentBlock = ContentBlock.Unknown(raw)

    override fun rawOf(value: ContentBlock): JsonObject =
        when (value) {
            is ContentBlock.Unknown -> value.raw
            is ContentBlock.Known -> error("${value::class.simpleName} is a known content block.")
        }
}
