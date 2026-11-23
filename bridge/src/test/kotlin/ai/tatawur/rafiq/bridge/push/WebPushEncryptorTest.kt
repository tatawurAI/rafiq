package ai.tatawur.rafiq.bridge.push

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPushEncryptorTest {
    @Test
    fun the_rfc_8291_example_encrypts_to_the_published_bytes() {
        // RFC 8291 section 5 and Appendix A.
        val plaintext = "When I grow up, I want to be a watermelon".toByteArray()
        val asPublic = unb64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
        val asPrivate = unb64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")
        val uaPublic = unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        val salt = unb64("DGv6ra1nlYgDCS1FRnbzlw")
        val auth = unb64("BTBZMqHH6r4Tts7J_aSIgg")
        val expected =
            unb64(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
                    "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
                    "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            )
        val privateKey =
            KeyFactory
                .getInstance("EC")
                .generatePrivate(ECPrivateKeySpec(BigInteger(1, asPrivate), WebPushEncryptor.P256))
        val keys = KeyPair(WebPushEncryptor.publicKey(asPublic), privateKey)

        val body = WebPushEncryptor().encrypt(plaintext, uaPublic, auth, keys, salt)

        assertContentEquals(expected, body)
    }

    @Test
    fun a_fresh_message_decrypts_for_the_phone_it_was_made_for() {
        val phone = TestPhoneKeys()
        val message = """{"v":1,"kind":"approval","session_id":"ses_1"}""".toByteArray()

        val body = WebPushEncryptor().encrypt(message, phone.publicBytes, phone.auth)

        assertContentEquals(message, phone.decrypt(body))
    }

    @Test
    fun only_a_point_on_the_curve_counts_as_a_p256_key() {
        val good = TestPhoneKeys().publicBytes
        val offCurve = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val compressed = good.copyOf().also { it[0] = 0x02 }

        assertTrue(WebPushEncryptor.isP256Point(good))
        assertFalse(WebPushEncryptor.isP256Point(offCurve))
        assertFalse(WebPushEncryptor.isP256Point(compressed))
        assertFalse(WebPushEncryptor.isP256Point(good.copyOf(33)))
    }
}
