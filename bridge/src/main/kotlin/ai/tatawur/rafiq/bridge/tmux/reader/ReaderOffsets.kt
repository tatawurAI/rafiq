package ai.tatawur.rafiq.bridge.tmux.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * How far the reader has read each session record, kept across bridge restarts so a restart backfills exactly
 * what it missed and nothing it already showed. A small JSON file in the data directory, mode 0600, holding
 * record paths and byte offsets only. With no data directory the offsets live in memory.
 */
class ReaderOffsets(
    private val file: Path?,
) {
    private val offsets = LinkedHashMap<String, Long>()

    init {
        file?.takeIf(Files::exists)?.let(::load)
    }

    @Synchronized
    fun get(recordPath: Path): Long? = offsets[recordPath.toString()]

    @Synchronized
    fun put(
        recordPath: Path,
        offset: Long,
    ) {
        val key = recordPath.toString()
        if (offsets[key] == offset) return
        offsets.remove(key)
        offsets[key] = offset
        while (offsets.size > MAX_RECORDS) offsets.remove(offsets.keys.first())
        save()
    }

    private fun load(path: Path) {
        val parsed = runCatching { Json.parseToJsonElement(Files.readString(path)) as? JsonObject }.getOrNull()
        parsed?.forEach { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { offsets[key] = it } }
    }

    private fun save() {
        val target = file ?: return
        val text = JsonObject(offsets.mapValues { JsonPrimitive(it.value) }).toString()
        try {
            Files.createDirectories(target.parent)
            val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.deleteIfExists(temp)
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(temp, text)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: IOException) {
            logger.warn("Could not save the reader's record offsets: {}", failure.toString())
        }
    }

    private companion object {
        const val MAX_RECORDS = 500
        val logger = LoggerFactory.getLogger(ReaderOffsets::class.java)
    }
}
