package ai.tatawur.rafiq.bridge.tmux.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the reader stopped reading one session record.
 *
 * @property offset the byte offset in the record up to which it was read.
 * @property seq the session's newest event `seq` at that moment. A reply in the log after it was shown live
 *   but not yet read from the record, so a backfill after a restart must not show it again.
 */
data class RecordCheckpoint(
    val offset: Long,
    val seq: Long = 0,
)

/**
 * How far the reader has read each session record, kept across bridge restarts so a restart backfills exactly
 * what it missed and nothing it already showed. A small JSON file in the data directory, mode 0600, holding
 * record paths, byte offsets and event `seq` numbers only. With no data directory the checkpoints live in memory.
 */
class ReaderOffsets(
    private val file: Path?,
) {
    private val checkpoints = LinkedHashMap<String, RecordCheckpoint>()

    init {
        file?.takeIf(Files::exists)?.let(::load)
    }

    @Synchronized
    fun get(recordPath: Path): RecordCheckpoint? = checkpoints[recordPath.toString()]

    @Synchronized
    fun put(
        recordPath: Path,
        checkpoint: RecordCheckpoint,
    ) {
        val key = recordPath.toString()
        if (checkpoints[key] == checkpoint) return
        checkpoints.remove(key)
        checkpoints[key] = checkpoint
        while (checkpoints.size > MAX_RECORDS) checkpoints.remove(checkpoints.keys.first())
        save()
    }

    private fun load(path: Path) {
        val parsed = runCatching { Json.parseToJsonElement(Files.readString(path)) as? JsonObject }.getOrNull()
        parsed?.forEach { (key, value) ->
            // An older bridge kept the offset alone.
            val checkpoint =
                when (value) {
                    is JsonPrimitive -> value.longOrNull?.let { RecordCheckpoint(it) }
                    is JsonObject -> value.long("offset")?.let { RecordCheckpoint(it, value.long("seq") ?: 0) }
                    else -> null
                }
            checkpoint?.let { checkpoints[key] = it }
        }
    }

    private fun save() {
        val target = file ?: return
        val text =
            JsonObject(
                checkpoints.mapValues { (_, checkpoint) ->
                    buildJsonObject {
                        put("offset", checkpoint.offset)
                        put("seq", checkpoint.seq)
                    }
                },
            ).toString()
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
