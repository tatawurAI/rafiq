package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.protocol.api.PushKind
import ai.tatawur.rafiq.protocol.api.PushPayload
import ai.tatawur.rafiq.protocol.protocolJson
import com.google.crypto.tink.apps.webpush.WebPushHybridDecrypt
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone decrypts pushes with the UnifiedPush connector, which carries its own copy of Tink's
 * `WebPushHybridDecrypt`. That copy differs from Tink's only in accepting a record size below 4096. The bridge
 * always writes 4096, so Tink's own class, built the way the connector's key manager builds it, stands in for the
 * phone here.
 */
class ConnectorInteropTest {
    @Test
    fun the_phones_decryptor_reads_every_padded_payload_kind_back_to_the_same_json() {
        val phone = TestPhoneKeys()
        val decryptor =
            WebPushHybridDecrypt
                .Builder()
                .withAuthSecret(phone.auth)
                .withRecipientPublicKey(phone.keys.public as ECPublicKey)
                .withRecipientPrivateKey(phone.keys.private as ECPrivateKey)
                .build()
        val payloads =
            listOf(
                PushPayload(kind = PushKind.APPROVAL, sessionId = "ses_1", approvalId = "apr_1"),
                PushPayload(kind = PushKind.APPROVAL_CLEARED, sessionId = "ses_1", approvalId = "apr_1"),
                PushPayload(kind = PushKind.TURN_DONE, sessionId = "ses_1"),
                PushPayload(kind = PushKind.ERROR, sessionId = "ses_1"),
                // Longer than one padding step: padded to the next multiple, never dropped.
                PushPayload(kind = PushKind.APPROVAL, sessionId = "s".repeat(300), approvalId = "apr_1"),
            )

        for (payload in payloads) {
            val json = protocolJson.encodeToString(PushPayload.serializer(), payload)
            val body = WebPushEncryptor().encrypt(json.toByteArray(), phone.publicBytes, phone.auth)

            assertEquals(json, decryptor.decrypt(body, null).decodeToString())
            val padded = body.size - WebPushEncryptor.HEADER_BYTES - TAG_BYTES
            assertEquals(0, padded % WebPushEncryptor.PADDED_BYTES, "padded to a whole number of steps")
        }
    }
}

private const val TAG_BYTES = 16
