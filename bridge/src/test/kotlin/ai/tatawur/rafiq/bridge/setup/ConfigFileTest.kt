package ai.tatawur.rafiq.bridge.setup

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigFileTest {
    @TempDir
    lateinit var home: Path

    private val file get() = home.resolve(".config/rafiq/bridge.conf")

    private fun writeFile(
        text: String,
        mode: String = "rw-------",
    ) {
        ConfigFile.write(file, text)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
    }

    @Test
    fun the_environment_wins_over_the_file_and_each_value_says_where_it_came_from() {
        writeFile("# my bridge\nRAFIQ_PORT=9000\nRAFIQ_DATA_DIR=/srv/rafiq\n")

        val settings =
            LoadedSettings.load(mapOf("HOME" to home.toString(), "RAFIQ_PORT" to "9100", "RAFIQ_PUSH" to " "))

        assertEquals("9100", settings.values["RAFIQ_PORT"])
        assertEquals(SettingOrigin.ENVIRONMENT, settings.origins["RAFIQ_PORT"])
        assertEquals("/srv/rafiq", settings.values["RAFIQ_DATA_DIR"])
        assertEquals(SettingOrigin.FILE, settings.origins["RAFIQ_DATA_DIR"])
        assertEquals(9100, settings.config().port)
        assertEquals(Path.of("/srv/rafiq"), settings.config().dataDir)
    }

    @Test
    fun the_file_is_found_under_xdg_config_home_when_that_is_absolute() {
        val xdg = home.resolve("xdg")
        assertEquals(
            xdg.resolve("rafiq/bridge.conf"),
            ConfigFile.locate(mapOf("HOME" to home.toString(), "XDG_CONFIG_HOME" to xdg.toString())),
        )
        assertEquals(file, ConfigFile.locate(mapOf("HOME" to home.toString(), "XDG_CONFIG_HOME" to "relative")))
    }

    @Test
    fun a_file_other_users_can_read_or_write_is_refused_and_a_private_one_is_read() {
        for (mode in listOf("rw-r-----", "rw----r--", "rw--w----", "rw-----w-")) {
            writeFile("RAFIQ_PORT=9000\n", mode)
            val refused = assertFailsWith<IllegalArgumentException>(mode) { ConfigFile.read(file) }
            assertTrue("chmod 600" in refused.message.orEmpty(), mode)
        }

        writeFile("RAFIQ_PORT=9000\n", "rw-------")
        assertEquals(mapOf("RAFIQ_PORT" to "9000"), ConfigFile.read(file))
    }

    @Test
    fun a_file_in_a_folder_others_can_write_is_refused() {
        writeFile("RAFIQ_PORT=9000\n")
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwxrwxrwx"))

        val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.read(file) }
        assertTrue("chmod 700" in refused.message.orEmpty())
    }

    @Test
    fun a_bad_line_names_the_line_and_never_its_value() {
        val secret = "replace-with-a-long-random-value"
        for (text in listOf("RAFIQ_PORT=9000\n$secret\n", "RAFIQ_DEVICE_TOKEN=\"$secret\"\n", "PATH=$secret\n")) {
            writeFile(text)
            val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.read(file) }
            assertTrue("line" in refused.message.orEmpty(), text)
            assertFalse(secret in refused.message.orEmpty(), text)
        }
        writeFile("RAFIQ_PORT=9000\nRAFIQ_PORT=9001\n")
        assertTrue(
            "second time" in assertFailsWith<IllegalArgumentException> { ConfigFile.read(file) }.message.orEmpty(),
        )
    }

    @Test
    fun an_update_replaces_values_in_place_and_keeps_comments_and_other_lines() {
        val before = "# set by hand\nRAFIQ_PORT=9000\n\nRAFIQ_ALLOWED_HOSTS=old.example.ts.net\n"

        val after =
            ConfigFile.update(
                before,
                mapOf(
                    "RAFIQ_ALLOWED_HOSTS" to "box.example.ts.net",
                    "RAFIQ_DATA_DIR" to "/srv/rafiq",
                ),
            )

        assertEquals(
            "# set by hand\nRAFIQ_PORT=9000\n\nRAFIQ_ALLOWED_HOSTS=box.example.ts.net\nRAFIQ_DATA_DIR=/srv/rafiq\n",
            after,
        )
    }

    @Test
    fun a_written_file_and_a_new_folder_are_private() {
        ConfigFile.write(file, "RAFIQ_PORT=9000\n")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
    }

    @Test
    fun a_symbolic_link_in_place_of_the_file_is_refused_even_when_its_target_is_private() {
        val target = home.resolve("elsewhere.conf")
        Files.writeString(target, "RAFIQ_PORT=9000\n")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        ConfigFile.write(file, "RAFIQ_PORT=9000\n")
        Files.delete(file)
        Files.createSymbolicLink(file, target)

        val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.read(file) }
        assertTrue("symbolic link" in refused.message.orEmpty())
    }

    @Test
    fun a_file_owned_by_another_user_id_than_the_process_is_refused() {
        writeFile("RAFIQ_PORT=9000\n")
        val uid = assertNotNull(ConfigFile.processUid)

        ConfigFile.checkPrivate(file, uid)
        val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.checkPrivate(file, uid + 1) }
        assertTrue(refused.message.orEmpty().startsWith("$file belongs to user id $uid"), refused.message)
    }

    @Test
    fun an_unknown_user_id_is_refused_rather_than_skipping_the_checks() {
        writeFile("RAFIQ_PORT=9000\n")

        val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.checkPrivate(file, uid = null) }
        assertTrue("could not tell which user" in refused.message.orEmpty())
    }

    @Test
    fun a_settings_folder_that_is_a_symbolic_link_is_refused() {
        val real = home.resolve("dotfiles/rafiq")
        ConfigFile.write(real.resolve("bridge.conf"), "RAFIQ_PORT=9000\n")
        Files.createDirectories(file.parent.parent)
        Files.createSymbolicLink(file.parent, real)

        val refused = assertFailsWith<IllegalArgumentException> { ConfigFile.read(file) }
        assertTrue("symbolic link" in refused.message.orEmpty())
    }

    @Test
    fun the_user_id_comes_from_proc_self_and_otherwise_from_id_u_by_its_full_path() {
        val uid = assertNotNull(ConfigFile.processUid)
        val noProc = home.resolve("no-proc")
        val idProgram = home.resolve("bin/id")
        val asked = mutableListOf<List<String>>()
        val answers501 =
            CommandRunner {
                asked += it
                CommandResult(0, "501\n")
            }

        assertEquals(uid, ConfigFile.effectiveUid(home, answers501, idProgram))
        assertTrue(asked.isEmpty())

        // No program at the full path: plain id from PATH is the fallback.
        assertEquals(501, ConfigFile.effectiveUid(noProc, answers501, idProgram))
        assertEquals(listOf("id", "-u"), asked.removeLast())

        Files.createDirectories(idProgram.parent)
        Files.writeString(idProgram, "#!/bin/sh\n")
        Files.setPosixFilePermissions(idProgram, PosixFilePermissions.fromString("rwx------"))
        assertEquals(501, ConfigFile.effectiveUid(noProc, answers501, idProgram))
        assertEquals(listOf(idProgram.toString(), "-u"), asked.removeLast())

        assertEquals(null, ConfigFile.effectiveUid(noProc, { CommandResult(127, "id: not found") }, idProgram))
    }
}
