package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.RevokeOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(60)
class DeviceServiceTest {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<DeviceFixture>()

    @AfterEach
    fun closeFixtures() = opened.forEach(DeviceFixture::close)

    private fun fixture(path: Path? = dir) = DeviceFixture(path).also(opened::add)

    @Test
    fun a_pairing_code_works_once_and_not_after_it_expires() =
        runBlocking {
            val devices = fixture()
            val code = devices.service.issueCode("phone").code
            // Typed in lower case without the hyphen, as people do.
            val spoken = code.replace("-", "").lowercase()

            assertIs<RedeemResult.Paired>(devices.service.redeem(spoken))
            assertEquals(RedeemResult.Invalid, devices.service.redeem(code))

            val late = devices.service.issueCode("tablet").code
            devices.clock.advance(10.minutes + 1.seconds)
            assertEquals(RedeemResult.Invalid, devices.service.redeem(late))
        }

    @Test
    fun two_requests_with_the_same_code_make_one_device() =
        runBlocking {
            val devices = fixture()
            val code = devices.service.issueCode("phone").code

            val results = (1..8).map { async { devices.service.redeem(code) } }.awaitAll()

            assertEquals(1, results.count { it is RedeemResult.Paired })
            assertEquals(1, devices.service.devices().size)
        }

    @Test
    fun wrong_codes_lock_out_even_the_right_one_until_the_window_passes() =
        runBlocking {
            val devices = fixture()
            val code = devices.service.issueCode("phone").code

            repeat(5) { assertEquals(RedeemResult.Invalid, devices.service.redeem("AAAAAAAA")) }
            val locked = devices.service.redeem(code)
            assertIs<RedeemResult.Throttled>(locked)
            assertTrue(locked.retryAfterSeconds in 1..300)

            devices.clock.advance(5.minutes)
            assertIs<RedeemResult.Paired>(devices.service.redeem(code))
        }

    @Test
    fun the_database_holds_a_hash_and_never_the_token_or_the_code() =
        runBlocking {
            val devices = fixture()
            val code = devices.service.issueCode("phone").code
            val paired = (devices.service.redeem(code) as RedeemResult.Paired).response
            val token = paired.token

            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("rafiq.db")}").use { db ->
                db.createStatement().use {
                    it.executeQuery("SELECT token_hash FROM devices").use { rows ->
                        assertTrue(rows.next())
                        assertContentEquals(
                            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
                            rows.getBytes(1),
                        )
                    }
                }
            }
            val everythingOnDisk =
                Files.list(dir).use { files ->
                    files.toList().filter(Files::isRegularFile).map { Files.readAllBytes(it) }
                }
            val plain = listOf(token, code.replace("-", ""), code)
            for (secret in plain) {
                assertFalse(
                    everythingOnDisk.any { String(it, Charsets.ISO_8859_1).contains(secret) },
                    "stored: $secret",
                )
            }
        }

    @Test
    fun the_legacy_token_is_a_device_until_it_is_revoked_and_stays_revoked_after_a_restart() =
        runBlocking {
            val devices = fixture()
            val legacyToken = "legacy-token-for-tests-".padEnd(64, 'x')
            devices.service.adoptLegacyToken(legacyToken)

            assertEquals(DeviceCheck.Active("legacy"), devices.service.verify(legacyToken))
            assertEquals(DeviceCheck.Unknown, devices.service.verify("$legacyToken-no"))

            assertEquals(RevokeOutcome.REVOKED, devices.service.revoke("legacy"))
            // The bridge restarts with the variable still set.
            devices.service.adoptLegacyToken(legacyToken)
            assertEquals(DeviceCheck.Revoked, devices.service.verify(legacyToken))
            assertEquals(RevokeOutcome.ALREADY_REVOKED, devices.service.revoke("legacy"))
            assertEquals(RevokeOutcome.NOT_FOUND, devices.service.revoke("dev_nope"))
        }

    @Test
    fun a_revoked_device_is_refused_while_its_token_is_still_valid_and_its_sockets_close() =
        runBlocking {
            val devices = fixture()
            val (id, token) = devices.pair()
            val (otherId, otherToken) = devices.pair("tablet")
            val closed = mutableListOf<String>()
            devices.registry.register(id, "events") { closed += "events:$it" }
            devices.registry.register(otherId, "events") { closed += "other" }
            assertEquals(DeviceCheck.Active(id), devices.service.verify(token))

            devices.service.revoke(id)

            assertEquals(DeviceCheck.Revoked, devices.service.verify(token))
            assertEquals(DeviceCheck.Active(otherId), devices.service.verify(otherToken))
            assertEquals(listOf("events:device revoked"), closed)
        }
}
