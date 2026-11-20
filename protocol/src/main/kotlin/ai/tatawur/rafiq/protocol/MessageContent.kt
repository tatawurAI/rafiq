package ai.tatawur.rafiq.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
     * A content block whose type this build does not recognise.
     *
     * @property type the wire type name as received, or an empty string if the block had none.
     * @property fields every other field of the block, unchanged.
     */
    data class Unknown(
        val type: String,
        val fields: JsonObject,
    ) : ContentBlock

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

    override fun unknown(
        type: String,
        fields: JsonObject,
    ): ContentBlock = ContentBlock.Unknown(type, fields)

    override fun unknownParts(value: ContentBlock): Pair<String, JsonObject> =
        when (value) {
            is ContentBlock.Unknown -> value.type to value.fields
            is ContentBlock.Known -> error("${value::class.simpleName} is a known content block.")
        }
}
