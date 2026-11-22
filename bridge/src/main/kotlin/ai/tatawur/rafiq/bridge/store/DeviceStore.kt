package ai.tatawur.rafiq.bridge.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/**
 * One paired device. There is no token here: the store keeps only its hash.
 *
 * @property lastSeenMs when the device last authenticated, rounded to the minute, or null if never.
 * @property revokedAtMs when the device was revoked, or null while it is active.
 */
data class DeviceRecord(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val lastSeenMs: Long?,
    val revokedAtMs: Long?,
)

/** What a token lookup found. */
sealed interface DeviceLookup {
    data class Found(
        val device: DeviceRecord,
    ) : DeviceLookup

    data object Unknown : DeviceLookup
}

/** What [DeviceStore.revoke] did. */
enum class RevokeOutcome { REVOKED, ALREADY_REVOKED, NOT_FOUND }

/**
 * The devices and pairing codes of the bridge, in the same SQLite file as the event log but on their own
 * connection, so a slow event write never delays a token check.
 *
 * Tokens and pairing codes are stored as SHA-256 digests. Both are random, and a token is 256 bits, so a
 * plain digest is the right tool: there is nothing to brute force, and a lookup by digest is cheap enough
 * to run on every request. The fetched digest is still compared in constant time.
 *
 * Everything runs on [dispatcher] over one connection, one task at a time.
 */
@Suppress("MagicNumber", "TooManyFunctions") // JDBC addresses columns by position; one class owns the connection.
class DeviceStore(
    jdbcUrl: String,
    private val dispatcher: CoroutineDispatcher,
    busyTimeoutMs: Int = BUSY_TIMEOUT_MS,
) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection(jdbcUrl)

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
            // The event log writes the same file. Wait for it instead of failing.
            statement.execute("PRAGMA busy_timeout = $busyTimeoutMs")
            SCHEMA.forEach(statement::execute)
        }
    }

    /**
     * Makes the shared token from `RAFIQ_DEVICE_TOKEN` the device `legacy`, so an app paired before
     * per-device tokens keeps working. A revoked `legacy` stays revoked whatever the variable holds, so
     * revoking it is final until the row is deleted by hand.
     */
    suspend fun ensureLegacy(
        tokenHash: ByteArray,
        nowMs: Long,
    ) = withContext(dispatcher) {
        connection
            .prepareStatement(
                """INSERT INTO devices (id, name, token_hash, created_at_ms) VALUES (?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET token_hash = excluded.token_hash WHERE revoked_at_ms IS NULL""",
            ).use { statement ->
                statement.setString(1, LEGACY_ID)
                statement.setString(2, LEGACY_ID)
                statement.setBytes(3, tokenHash)
                statement.setLong(4, nowMs)
                statement.executeUpdate()
            }
        Unit
    }

    /** Stores a pairing code (as its digest) for [name], and forgets codes that are spent or expired. */
    suspend fun addPairingCode(
        codeHash: ByteArray,
        name: String,
        expiresAtMs: Long,
        nowMs: Long,
    ) = withContext(dispatcher) {
        transaction {
            connection
                .prepareStatement(PURGE_CODES)
                .use {
                    it.setLong(1, nowMs)
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO pairing_codes (code_hash, name, expires_at_ms) VALUES (?, ?, ?)",
                ).use {
                    it.setBytes(1, codeHash)
                    it.setString(2, name)
                    it.setLong(3, expiresAtMs)
                    it.executeUpdate()
                }
        }
    }

    /**
     * Spends the code and creates the device in one transaction. The `used_at_ms IS NULL` condition in the
     * update is what makes a code single use, even when two requests present it at the same moment.
     *
     * @return the new device, or null when the code is unknown, expired or already used.
     */
    suspend fun redeem(
        codeHash: ByteArray,
        deviceId: String,
        tokenHash: ByteArray,
        nowMs: Long,
    ): DeviceRecord? =
        withContext(dispatcher) {
            transaction {
                val name =
                    connection
                        .prepareStatement(FIND_LIVE_CODE)
                        .use { statement ->
                            statement.setBytes(1, codeHash)
                            statement.setLong(2, nowMs)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                        }
                if (name == null || !spend(codeHash, nowMs)) return@transaction null
                connection
                    .prepareStatement("INSERT INTO devices (id, name, token_hash, created_at_ms) VALUES (?, ?, ?, ?)")
                    .use {
                        it.setString(1, deviceId)
                        it.setString(2, name)
                        it.setBytes(3, tokenHash)
                        it.setLong(4, nowMs)
                        it.executeUpdate()
                    }
                DeviceRecord(deviceId, name, nowMs, null, null)
            }
        }

    // The condition `used_at_ms IS NULL` is what makes a code single use under concurrent requests.
    private fun spend(
        codeHash: ByteArray,
        nowMs: Long,
    ): Boolean =
        connection
            .prepareStatement(
                "UPDATE pairing_codes SET used_at_ms = ? WHERE code_hash = ? AND used_at_ms IS NULL",
            ).use {
                it.setLong(1, nowMs)
                it.setBytes(2, codeHash)
                it.executeUpdate() == 1
            }

    /** Finds the device whose token digest is [tokenHash]. Revoked ones too, so callers can tell them apart. */
    suspend fun lookup(tokenHash: ByteArray): DeviceLookup =
        withContext(dispatcher) {
            connection
                .prepareStatement("$SELECT_DEVICE, token_hash FROM devices WHERE token_hash = ?")
                .use { statement ->
                    statement.setBytes(1, tokenHash)
                    statement.executeQuery().use { rows ->
                        // The index found it by digest. Compare again in constant time before trusting it.
                        if (rows.next() && MessageDigest.isEqual(rows.getBytes(6), tokenHash)) {
                            DeviceLookup.Found(rows.record())
                        } else {
                            DeviceLookup.Unknown
                        }
                    }
                }
        }

    /** Records that [id] authenticated at [nowMs]. */
    suspend fun touch(
        id: String,
        nowMs: Long,
    ) = withContext(dispatcher) {
        connection.prepareStatement("UPDATE devices SET last_seen_ms = ? WHERE id = ?").use {
            it.setLong(1, nowMs)
            it.setString(2, id)
            it.executeUpdate()
        }
        Unit
    }

    /** Every device, newest first, revoked ones included. */
    suspend fun list(): List<DeviceRecord> =
        withContext(dispatcher) {
            connection
                .prepareStatement("$SELECT_DEVICE FROM devices ORDER BY created_at_ms DESC, id")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.record()) }
                    }
                }
        }

    /** Marks [id] revoked. The row stays, so the next request with its token can be told apart from a wrong one. */
    suspend fun revoke(
        id: String,
        nowMs: Long,
    ): RevokeOutcome =
        withContext(dispatcher) {
            val changed =
                connection
                    .prepareStatement("UPDATE devices SET revoked_at_ms = ? WHERE id = ? AND revoked_at_ms IS NULL")
                    .use {
                        it.setLong(1, nowMs)
                        it.setString(2, id)
                        it.executeUpdate()
                    }
            when {
                changed == 1 -> RevokeOutcome.REVOKED
                list().any { it.id == id } -> RevokeOutcome.ALREADY_REVOKED
                else -> RevokeOutcome.NOT_FOUND
            }
        }

    override fun close() = connection.close()

    @Suppress("TooGenericExceptionCaught") // Roll back on any failure, then rethrow it.
    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun java.sql.ResultSet.record() =
        DeviceRecord(
            id = getString(1),
            name = getString(2),
            createdAtMs = getLong(3),
            lastSeenMs = getLong(4).takeUnless { wasNull() },
            revokedAtMs = getLong(5).takeUnless { wasNull() },
        )

    companion object {
        /** The id of the device the shared `RAFIQ_DEVICE_TOKEN` stands for. */
        const val LEGACY_ID = "legacy"
        private const val BUSY_TIMEOUT_MS = 5000

        /** The warning for a bridge with no data folder, or null when there is one. */
        fun persistenceWarning(dataDir: Path?): String? =
            if (dataDir == null) {
                "RAFIQ_DATA_DIR is not set, so paired devices and revocations are not saved. After a restart every " +
                    "phone must pair again, and a revoked legacy token works again. Set RAFIQ_DATA_DIR."
            } else {
                null
            }

        private const val SELECT_DEVICE = "SELECT id, name, created_at_ms, last_seen_ms, revoked_at_ms"
        private const val PURGE_CODES = "DELETE FROM pairing_codes WHERE used_at_ms IS NOT NULL OR expires_at_ms <= ?"
        private const val FIND_LIVE_CODE =
            "SELECT name FROM pairing_codes WHERE code_hash = ? AND used_at_ms IS NULL AND expires_at_ms > ?"

        private val SCHEMA =
            listOf(
                """CREATE TABLE IF NOT EXISTS devices (
                     id TEXT PRIMARY KEY,
                     name TEXT NOT NULL,
                     token_hash BLOB NOT NULL UNIQUE,
                     created_at_ms INTEGER NOT NULL,
                     last_seen_ms INTEGER,
                     revoked_at_ms INTEGER
                   )""",
                """CREATE TABLE IF NOT EXISTS pairing_codes (
                     code_hash BLOB PRIMARY KEY,
                     name TEXT NOT NULL,
                     expires_at_ms INTEGER NOT NULL,
                     used_at_ms INTEGER
                   )""",
            )

        /** Opens the store in the same file as the event log, or in memory when there is no data folder. */
        fun open(
            dataDir: Path?,
            dispatcher: CoroutineDispatcher,
            busyTimeoutMs: Int = BUSY_TIMEOUT_MS,
        ): DeviceStore {
            persistenceWarning(dataDir)?.let { LoggerFactory.getLogger(DeviceStore::class.java).warn(it) }
            val url =
                if (dataDir == null) {
                    "jdbc:sqlite::memory:"
                } else {
                    val file = dataDir.resolve("rafiq.db")
                    createPrivateFile(dataDir, file)
                    "jdbc:sqlite:$file"
                }
            return DeviceStore(url, dispatcher, busyTimeoutMs)
        }

        private fun createPrivateFile(
            dir: Path,
            file: Path,
        ) {
            if ("posix" !in dir.fileSystem.supportedFileAttributeViews()) {
                Files.createDirectories(dir)
                return
            }
            val folderMode = PosixFilePermissions.fromString("rwx------")
            val fileMode = PosixFilePermissions.fromString("rw-------")
            if (!Files.isDirectory(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(folderMode))
            Files.setPosixFilePermissions(dir, folderMode)
            if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(fileMode))
            Files.setPosixFilePermissions(file, fileMode)
        }
    }
}
