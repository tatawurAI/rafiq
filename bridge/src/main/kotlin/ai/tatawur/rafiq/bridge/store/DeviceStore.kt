package ai.tatawur.rafiq.bridge.store

import ai.tatawur.rafiq.bridge.hook.HookConsent
import ai.tatawur.rafiq.bridge.hook.HookConsentStore
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

/**
 * Where one device's pushes go: its UnifiedPush endpoint and the Web Push keys from its registration, decoded.
 *
 * @property p256dh the phone's P-256 public key, 65 bytes uncompressed.
 * @property auth the 16-byte authentication secret.
 */
class StoredPush(
    val endpoint: String,
    val p256dh: ByteArray,
    val auth: ByteArray,
) {
    // The endpoint is a capability: anyone holding it can push to the phone.
    override fun toString(): String = "StoredPush(endpoint=<hidden>)"
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
) : AutoCloseable,
    HookConsentStore {
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

    /**
     * Marks [id] revoked and deletes its push registration in the same transaction, so no push reaches a
     * revoked phone. The device row stays, so the next request with its token can be told apart from a wrong one.
     */
    suspend fun revoke(
        id: String,
        nowMs: Long,
    ): RevokeOutcome =
        withContext(dispatcher) {
            val changed =
                transaction {
                    deletePushRow(id)
                    connection
                        .prepareStatement("UPDATE devices SET revoked_at_ms = ? WHERE id = ? AND revoked_at_ms IS NULL")
                        .use {
                            it.setLong(1, nowMs)
                            it.setString(2, id)
                            it.executeUpdate()
                        }
                }
            when {
                changed == 1 -> RevokeOutcome.REVOKED
                list().any { it.id == id } -> RevokeOutcome.ALREADY_REVOKED
                else -> RevokeOutcome.NOT_FOUND
            }
        }

    /** Records that hooks were installed in a project, replacing an earlier record for it. */
    override suspend fun recordHookConsent(consent: HookConsent) =
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    """INSERT INTO hook_consents (project_path, file_path, written_sha256, device_id, at_ms)
                       VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(project_path) DO UPDATE SET file_path = excluded.file_path,
                         written_sha256 = excluded.written_sha256, device_id = excluded.device_id,
                         at_ms = excluded.at_ms""",
                ).use {
                    it.setString(1, consent.projectPath)
                    it.setString(2, consent.filePath)
                    it.setString(3, consent.writtenSha256)
                    it.setString(4, consent.deviceId)
                    it.setLong(5, consent.atMs)
                    it.executeUpdate()
                }
            Unit
        }

    override suspend fun hookConsent(projectPath: String): HookConsent? =
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    "SELECT project_path, file_path, written_sha256, device_id, at_ms FROM hook_consents " +
                        "WHERE project_path = ?",
                ).use { statement ->
                    statement.setString(1, projectPath)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            HookConsent(
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                rows.getLong(5),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun forgetHookConsent(projectPath: String) =
        withContext(dispatcher) {
            connection.prepareStatement("DELETE FROM hook_consents WHERE project_path = ?").use {
                it.setString(1, projectPath)
                it.executeUpdate()
            }
            Unit
        }

    /**
     * Stores where [deviceId]'s pushes go, replacing an earlier registration. Only an active device can hold one,
     * so a registration that races a revoke is never left behind.
     *
     * @return false when the device is unknown or revoked, and nothing was stored.
     */
    suspend fun setPush(
        deviceId: String,
        push: StoredPush,
        nowMs: Long,
    ): Boolean =
        withContext(dispatcher) {
            connection
                .prepareStatement(
                    """INSERT INTO push_registrations (device_id, endpoint, p256dh, auth, updated_at_ms)
                       SELECT ?, ?, ?, ?, ? WHERE EXISTS
                         (SELECT 1 FROM devices WHERE id = ? AND revoked_at_ms IS NULL)
                       ON CONFLICT(device_id) DO UPDATE SET endpoint = excluded.endpoint, p256dh = excluded.p256dh,
                         auth = excluded.auth, updated_at_ms = excluded.updated_at_ms""",
                ).use {
                    it.setString(1, deviceId)
                    it.setString(2, push.endpoint)
                    it.setBytes(3, push.p256dh)
                    it.setBytes(4, push.auth)
                    it.setLong(5, nowMs)
                    it.setString(6, deviceId)
                    it.executeUpdate() == 1
                }
        }

    /**
     * Deletes [deviceId]'s push registration. With [endpoint], only while it still points there, so a push
     * service's "gone" for an old endpoint never deletes a registration the phone has just replaced.
     */
    suspend fun deletePush(
        deviceId: String,
        endpoint: String? = null,
    ) = withContext(dispatcher) {
        if (endpoint == null) {
            deletePushRow(deviceId)
        } else {
            connection.prepareStatement("DELETE FROM push_registrations WHERE device_id = ? AND endpoint = ?").use {
                it.setString(1, deviceId)
                it.setString(2, endpoint)
                it.executeUpdate()
            }
        }
        Unit
    }

    /** The push registration of [deviceId] while the device is active, or null. */
    suspend fun push(deviceId: String): StoredPush? =
        withContext(dispatcher) {
            connection.prepareStatement("$SELECT_PUSH AND p.device_id = ?").use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.push().second else null }
            }
        }

    /** The ids of the active devices that registered for pushes. */
    suspend fun pushDevices(): List<String> =
        withContext(dispatcher) {
            connection.prepareStatement(SELECT_PUSH).use { statement ->
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.push().first) } }
            }
        }

    private fun deletePushRow(deviceId: String) {
        connection.prepareStatement("DELETE FROM push_registrations WHERE device_id = ?").use {
            it.setString(1, deviceId)
            it.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.push() = getString(1) to StoredPush(getString(2), getBytes(3), getBytes(4))

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
        private const val SELECT_PUSH =
            "SELECT p.device_id, p.endpoint, p.p256dh, p.auth FROM push_registrations p " +
                "JOIN devices d ON d.id = p.device_id WHERE d.revoked_at_ms IS NULL"
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
                """CREATE TABLE IF NOT EXISTS hook_consents (
                     project_path TEXT PRIMARY KEY,
                     file_path TEXT NOT NULL,
                     written_sha256 TEXT NOT NULL,
                     device_id TEXT NOT NULL,
                     at_ms INTEGER NOT NULL
                   )""",
                """CREATE TABLE IF NOT EXISTS pairing_codes (
                     code_hash BLOB PRIMARY KEY,
                     name TEXT NOT NULL,
                     expires_at_ms INTEGER NOT NULL,
                     used_at_ms INTEGER
                   )""",
                // One registration per device. The endpoint is a capability, like a token, so the file stays 0600.
                """CREATE TABLE IF NOT EXISTS push_registrations (
                     device_id TEXT PRIMARY KEY REFERENCES devices(id),
                     endpoint TEXT NOT NULL,
                     p256dh BLOB NOT NULL,
                     auth BLOB NOT NULL,
                     updated_at_ms INTEGER NOT NULL
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
