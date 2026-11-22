package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import ai.tatawur.rafiq.bridge.server.DeviceFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The commands against a real Unix socket. Unix socket paths are short (104 bytes on macOS), so the data
 * folder is made under /tmp instead of in JUnit's long temporary folder.
 */
@Timeout(60)
class DeviceAdminTest {
    private lateinit var dataDir: Path
    private lateinit var devices: DeviceFixture
    private lateinit var server: DeviceAdminServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeEach
    fun start() {
        dataDir = Files.createTempDirectory(Path.of("/tmp"), "rq")
        devices = DeviceFixture(dataDir)
        server =
            DeviceAdminServer(
                DeviceAdminServer.socketPathFor(dataDir),
                devices.service,
                DeviceAdminServer.urlFor("127.0.0.1", listOf("box.example.ts.net")),
                scope,
            ).start()
    }

    @AfterEach
    fun stop() {
        server.close()
        scope.cancel()
        devices.close()
        dataDir.toFile().deleteRecursively()
    }

    private class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code =
            DeviceCli.run(
                args.toList(),
                mapOf("RAFIQ_DATA_DIR" to dataDir.toString()),
                PrintStream(out),
                PrintStream(err),
            )
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun pair_devices_and_revoke_work_over_the_socket_and_a_revoke_cuts_the_device_off() {
        val pair = cli("pair", "--name", "Pixel")
        assertEquals(0, pair.code, pair.err)
        assertTrue("https://box.example.ts.net" in pair.out)
        val code = Regex("Pairing code for Pixel: ([A-Z2-9]{4}-[A-Z2-9]{4})").find(pair.out)!!.groupValues[1]

        val paired =
            kotlinx.coroutines
                .runBlocking {
                    devices.service.redeem(code) as ai.tatawur.rafiq.bridge.server.RedeemResult.Paired
                }.response
        val listed = cli("devices")
        assertTrue(paired.deviceId in listed.out && "Pixel" in listed.out && "active" in listed.out)
        assertTrue(paired.token !in listed.out)

        val revoked = cli("revoke", paired.deviceId)
        assertEquals(0, revoked.code, revoked.err)
        assertTrue("revoked" in cli("devices").out)
        assertEquals(1, cli("revoke", "dev_nope").code)
        assertEquals(2, cli("revoke").code)
    }

    @Test
    fun the_socket_lives_in_an_owner_only_folder_and_is_owner_only() {
        val socket = DeviceAdminServer.socketPathFor(dataDir)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parent)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
    }

    @Test
    fun a_folder_that_is_too_open_or_a_symlink_is_refused_and_a_second_bridge_cannot_take_the_socket() {
        val socket = DeviceAdminServer.socketPathFor(dataDir)
        server.close()
        Files.setPosixFilePermissions(socket.parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        val loose = assertFailsWith<IllegalStateException> { restart() }
        assertTrue("rwx------" in loose.message.orEmpty())

        Files.setPosixFilePermissions(socket.parent, PosixFilePermissions.fromString("rwx------"))
        server = restart()
        val second = assertFailsWith<IllegalStateException> { restart() }
        assertTrue("already answering" in second.message.orEmpty())
    }

    @Test
    fun a_command_with_no_bridge_running_says_so() {
        server.close()
        val result = cli("devices")
        assertEquals(1, result.code)
        assertTrue("Is it running?" in result.err)
    }

    private fun restart() =
        DeviceAdminServer(DeviceAdminServer.socketPathFor(dataDir), devices.service, null, scope).start()

    @Test
    fun a_busy_database_gets_an_error_answer_and_the_socket_keeps_answering() {
        server.close()
        devices.close()
        // A short busy wait, so the test does not sit for the production five seconds.
        devices = DeviceFixture(dataDir, busyTimeoutMs = 200)
        server = restart()
        val socket = DeviceAdminServer.socketPathFor(dataDir)
        assertTrue(
            java.nio.file.Files
                .exists(socket),
        )

        java.sql.DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve("rafiq.db")}").use { other ->
            other.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            val busy = cli("pair", "--name", "Pixel")
            assertEquals(1, busy.code)
            assertTrue("Try again" in busy.err, busy.err)
            other.createStatement().use { it.execute("ROLLBACK") }
        }

        val after = cli("devices")
        assertEquals(0, after.code, after.err)
    }

    @Test
    fun a_command_gives_up_when_the_bridge_never_answers() {
        server.close()
        val socket = DeviceAdminServer.socketPathFor(dataDir)
        val mute =
            java.nio.channels.ServerSocketChannel
                .open(java.net.StandardProtocolFamily.UNIX)
        mute.bind(java.net.UnixDomainSocketAddress.of(socket))
        try {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val code =
                DeviceCli.run(
                    listOf("devices"),
                    mapOf("RAFIQ_DATA_DIR" to dataDir.toString()),
                    PrintStream(out),
                    PrintStream(err),
                    answerTimeout = kotlin.time.Duration.parse("300ms"),
                )
            assertEquals(1, code)
            assertTrue("did not answer" in err.toString(), err.toString())
        } finally {
            mute.close()
            java.nio.file.Files
                .deleteIfExists(socket)
        }
    }

    @Test
    fun a_data_folder_whose_socket_path_is_too_long_fails_and_names_the_variable() {
        val long = Path.of("/tmp/" + "a".repeat(150))
        val failure =
            assertFailsWith<IllegalStateException> {
                DeviceAdminServer(DeviceAdminServer.socketPathFor(long), devices.service, null, scope).start()
            }
        assertTrue("RAFIQ_DATA_DIR" in failure.message.orEmpty(), failure.message)
        assertTrue(!Files.exists(long))
    }

    @Test
    fun a_bridge_without_a_data_folder_warns_that_revocation_is_not_saved() {
        assertTrue(
            "revocations are not saved" in
                ai.tatawur.rafiq.bridge.store.DeviceStore
                    .persistenceWarning(null)
                    .orEmpty(),
        )
        assertEquals(
            null,
            ai.tatawur.rafiq.bridge.store.DeviceStore
                .persistenceWarning(dataDir),
        )
    }
}
