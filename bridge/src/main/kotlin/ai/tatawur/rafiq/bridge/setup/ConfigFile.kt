package ai.tatawur.rafiq.bridge.setup

import ai.tatawur.rafiq.bridge.BridgeConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The bridge's settings file, `bridge.conf` in `$XDG_CONFIG_HOME/rafiq/` (ADR 0014). It holds the same settings as
 * the environment variables, under the same names, one `NAME=value` per line. Blank lines and lines starting with
 * `#` are ignored. A value is taken as written, with no quoting and no expansion.
 *
 * The file can name agents to run and the folder the database lives in, so the bridge reads it only when nobody but
 * its owner can read or change it.
 */
object ConfigFile {
    /** The file's name inside the `rafiq` config folder. */
    const val NAME = "bridge.conf"

    /**
     * Where the file lives for [environment]: `$XDG_CONFIG_HOME/rafiq/bridge.conf`, or `~/.config/rafiq/bridge.conf`
     * when that is unset or not absolute, as the XDG spec says. Null when neither variable gives a folder.
     */
    fun locate(environment: Map<String, String>): Path? {
        val xdg = environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute }
        val home = environment["HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { it.isAbsolute }
        val configHome = xdg ?: home?.resolve(".config") ?: return null
        return configHome.resolve("rafiq").resolve(NAME)
    }

    /**
     * Reads [path] after checking that only its owner can use it. Returns an empty map when the file does not exist.
     *
     * @throws IllegalArgumentException when the file or its folder is open to other users, or a line is not a setting.
     *   The message names the file and the line number, never a value, because a value can be a secret.
     */
    fun read(path: Path): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        checkPrivate(path)
        return parse(Files.readString(path), path)
    }

    /**
     * Refuses [path] if anyone but its owner can read or write it, if it belongs to another user, or if others can
     * write to the folder that holds it, since they could then swap the file. Skipped on a file system without POSIX
     * permissions.
     */
    fun checkPrivate(path: Path) {
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
        val modes = Files.getPosixFilePermissions(path)
        val open = modes.intersect(NOT_OWNER_READ_WRITE)
        require(open.isEmpty()) {
            "$path can be read or written by other users, so the bridge will not use it. Make it private with: " +
                "chmod 600 $path"
        }
        val owner = Files.getOwner(path).name
        val user = System.getProperty("user.name")
        require(
            owner == user,
        ) { "$path belongs to $owner, not to $user, who runs the bridge, so the bridge will not use it." }
        val folder = path.toAbsolutePath().parent
        val folderModes = Files.getPosixFilePermissions(folder)
        require(PosixFilePermission.GROUP_WRITE !in folderModes && PosixFilePermission.OTHERS_WRITE !in folderModes) {
            "Other users can write to $folder, so they could replace ${path.fileName}. Fix it with: chmod 700 $folder"
        }
    }

    /** True when [checkPrivate] would refuse [path]. */
    fun isOpenToOthers(path: Path): Boolean = runCatching { checkPrivate(path) }.isFailure

    /** Makes [path] readable and writable by its owner only (mode 0600). */
    fun makePrivate(path: Path) {
        Files.setPosixFilePermissions(path, OWNER_ONLY_FILE)
    }

    /**
     * Parses the text of a settings file. [source] names the file in error messages.
     *
     * @throws IllegalArgumentException for a line that is not `NAME=value`, a name that is not a `RAFIQ_` setting, a
     *   quoted value, or a name given twice.
     */
    fun parse(
        text: String,
        source: Path,
    ): Map<String, String> {
        val values = linkedMapOf<String, String>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val where = "$source line ${index + 1}"
            val name = line.substringBefore('=', "").trim()
            require(SETTING_NAME.matches(name)) {
                "$where is not a setting. Each line must look like RAFIQ_NAME=value."
            }
            val value = line.substringAfter('=').trim()
            require(!(value.length >= 2 && value.first() in QUOTES && value.last() == value.first())) {
                "$where puts $name in quotes. Write the value without quotes."
            }
            require(name !in values) { "$where sets $name a second time." }
            values[name] = value
        }
        return values
    }

    /**
     * Returns [text] with [changes] applied: a name already in the file keeps its line and gets the new value, and a
     * new name is added at the end. Comments and every other line stay as they were.
     */
    fun update(
        text: String,
        changes: Map<String, String>,
    ): String {
        val pending = LinkedHashMap(changes)
        val lines =
            text.lines().dropLastWhile(String::isEmpty).map { raw ->
                val trimmed = raw.trim()
                val name = trimmed.substringBefore('=', "").trim()
                if (!trimmed.startsWith('#') && name in pending) "$name=${pending.remove(name)}" else raw
            }
        return (lines + pending.map { (name, value) -> "$name=$value" }).joinToString("\n", postfix = "\n")
    }

    /**
     * Writes [text] to [path] so that it is never readable by others, not even for a moment: the folder is created
     * with mode 0700 if missing, and the text goes to a 0600 temporary file that then replaces [path].
     */
    fun write(
        path: Path,
        text: String,
    ) {
        val folder = path.toAbsolutePath().parent
        if (!Files.isDirectory(folder)) {
            Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(OWNER_ONLY_FOLDER))
        }
        val temporary =
            Files.createTempFile(
                folder,
                ".bridge",
                ".conf",
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
            )
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private val SETTING_NAME = Regex("RAFIQ_[A-Z0-9_]+")
    private val QUOTES = setOf('"', '\'')

    private val NOT_OWNER_READ_WRITE =
        setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
        )
    private val OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------")
    private val OWNER_ONLY_FOLDER = PosixFilePermissions.fromString("rwx------")
}

/** Where a setting's value came from, as `rafiq-bridge setup` prints it. */
enum class SettingOrigin(
    val label: String,
) {
    ENVIRONMENT("environment"),
    FILE(ConfigFile.NAME),
}

/**
 * The bridge's settings after merging the file with the environment.
 *
 * @property values every `RAFIQ_` setting, plus the other variables the bridge reads, with the environment winning.
 * @property origins where each `RAFIQ_` setting in [values] came from.
 * @property file the settings file that was read, or null when there is no home folder to look in.
 */
class LoadedSettings(
    val values: Map<String, String>,
    val origins: Map<String, SettingOrigin>,
    val file: Path?,
) {
    /** The bridge's configuration, checked exactly as [BridgeConfig.fromEnvironment] checks the environment alone. */
    fun config(): BridgeConfig = BridgeConfig.fromEnvironment(values)

    // Values can hold a token. Names and origins only.
    override fun toString(): String = "LoadedSettings(file=$file, origins=$origins)"

    companion object {
        /**
         * Reads the settings file for [environment] and lays [environment] over it. A variable that is set but blank
         * counts as unset, as [BridgeConfig] treats it, so it does not hide the file's value.
         *
         * @throws IllegalArgumentException when the file is open to other users or malformed. See [ConfigFile.read].
         */
        fun load(environment: Map<String, String>): LoadedSettings {
            val file = ConfigFile.locate(environment)
            val fromFile = file?.let(ConfigFile::read).orEmpty()
            val fromEnvironment = environment.filterValues { it.isNotBlank() }
            val origins =
                fromFile.keys.associateWith { SettingOrigin.FILE } +
                    fromEnvironment.keys.filter { it.startsWith("RAFIQ_") }.associateWith { SettingOrigin.ENVIRONMENT }
            return LoadedSettings(environment + fromFile + fromEnvironment, origins, file)
        }
    }
}
