package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.DeviceLookup
import ai.tatawur.rafiq.bridge.store.DeviceRecord
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.RevokeOutcome
import ai.tatawur.rafiq.protocol.api.PairResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a token check decided. */
sealed interface DeviceCheck {
    data class Active(
        val deviceId: String,
    ) : DeviceCheck

    /** The token is genuine but its device was revoked. The app clears it and asks to pair again. */
    data object Revoked : DeviceCheck

    data object Unknown : DeviceCheck
}

/** Decides which device, if any, a token belongs to. Called on every request and every socket handshake. */
fun interface DeviceVerifier {
    suspend fun verify(token: String): DeviceCheck
}

/** What [DeviceService.redeem] did with a pairing code. */
sealed interface RedeemResult {
    data class Paired(
        val response: PairResponse,
    ) : RedeemResult

    /** Wrong, expired or already used. One answer for all three, so a guess learns nothing. */
    data object Invalid : RedeemResult

    data class Throttled(
        val retryAfterSeconds: Long,
    ) : RedeemResult
}

/** A pairing code the owner can read out. [code] is shown once and never stored. */
class IssuedCode(
    val code: String,
    val name: String,
    val expiresAtMs: Long,
) {
    // Holds a secret, so it must never print one.
    override fun toString(): String = "IssuedCode(name=$name, expiresAtMs=$expiresAtMs)"
}

/**
 * Pairing, token checks and revocation, with no HTTP in it so tests and the admin socket can drive it
 * directly.
 *
 * - A pairing code is 8 characters from a 32-symbol alphabet (40 bits), valid for [codeLifetime] and
 *   spent by the first successful `POST /pair`.
 * - Wrong codes are counted across all callers, because behind `tailscale serve` every caller is
 *   `127.0.0.1`. After [maxFailures] wrong codes within [failureWindow], every attempt gets
 *   [RedeemResult.Throttled] until the oldest failure ages out, including a correct one.
 * - Making a new code with `rafiq-bridge pair` clears the count, so a stranger's wrong guesses cannot lock
 *   the owner out for good. The cost is that someone at the CLI can reset the limit, which already means
 *   they can pair a device.
 * - Attempts are serialised by one lock held across the database check, so parallel guesses cannot
 *   exceed the limit.
 * - A device token is 256 random bits. Only its digest is stored.
 *
 * @param onPaired runs with the new device's id after each successful pairing, outside the attempt lock. The
 *   Claude reader uses it so a device paired while approvals wait can release them when it is revoked.
 */
class DeviceService(
    private val store: DeviceStore,
    private val registry: InMemoryConnectionRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val codeLifetime: Duration = 10.minutes,
    private val maxFailures: Int = MAX_FAILURES,
    private val failureWindow: Duration = 5.minutes,
    private val onPaired: suspend (deviceId: String) -> Unit = {},
) : DeviceVerifier {
    private val attempts = Mutex()
    private val failures = ArrayDeque<Long>()
    private val lastTouchedMs = ConcurrentHashMap<String, Long>()

    /** Makes the shared `RAFIQ_DEVICE_TOKEN` the device `legacy`. See [DeviceStore.ensureLegacy]. */
    suspend fun adoptLegacyToken(token: String) = store.ensureLegacy(digest(token), clock.millis())

    /** Issues a code for a device called [name]. */
    suspend fun issueCode(name: String): IssuedCode {
        val code = CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.concatToString()
        val expiresAtMs = clock.millis() + codeLifetime.inWholeMilliseconds
        store.addPairingCode(digest(code), name, expiresAtMs, clock.millis())
        // The owner is at the CLI, so a lockout that strangers caused ends here. Otherwise anyone who can reach
        // /pair could keep you from ever pairing.
        attempts.withLock { failures.clear() }
        return IssuedCode("${code.take(CODE_GROUP)}-${code.drop(CODE_GROUP)}", name, expiresAtMs)
    }

    /** Spends [code] and returns the new device's token. */
    suspend fun redeem(code: String): RedeemResult =
        redeemLocked(code).also { result -> if (result is RedeemResult.Paired) onPaired(result.response.deviceId) }

    private suspend fun redeemLocked(code: String): RedeemResult =
        attempts.withLock {
            val now = clock.millis()
            while (failures.isNotEmpty() && now - failures.first() >= failureWindow.inWholeMilliseconds) {
                failures.removeFirst()
            }
            if (failures.size >= maxFailures) {
                val waitMs = failures.first() + failureWindow.inWholeMilliseconds - now
                return@withLock RedeemResult.Throttled(maxOf(1, (waitMs + MS - 1) / MS))
            }
            val token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES))
            val deviceId = DEVICE_PREFIX + randomBytes(ID_BYTES).joinToString("") { "%02x".format(it) }
            val device = store.redeem(digest(normalise(code)), deviceId, digest(token), now)
            if (device == null) {
                failures.addLast(now)
                RedeemResult.Invalid
            } else {
                RedeemResult.Paired(PairResponse(device.id, device.name, token))
            }
        }

    override suspend fun verify(token: String): DeviceCheck {
        val device = (store.lookup(digest(token)) as? DeviceLookup.Found)?.device
        return when {
            device == null -> {
                DeviceCheck.Unknown
            }

            device.revokedAtMs != null -> {
                DeviceCheck.Revoked
            }

            else -> {
                touch(device.id)
                DeviceCheck.Active(device.id)
            }
        }
    }

    suspend fun devices(): List<DeviceRecord> = store.list()

    /**
     * Revokes [id] and closes its open sockets. The database changes first, so a handshake that races
     * this call is either refused or registers after the registry started refusing it.
     */
    suspend fun revoke(id: String): RevokeOutcome {
        val outcome = store.revoke(id, clock.millis())
        if (outcome != RevokeOutcome.NOT_FOUND) registry.closeAll(id, REVOKED_REASON)
        return outcome
    }

    // Once a minute is enough for "last seen", and keeps a write off every request.
    private suspend fun touch(id: String) {
        val now = clock.millis()
        val previous = lastTouchedMs.put(id, now)
        if (previous == null || now - previous >= TOUCH_EVERY.inWholeMilliseconds) store.touch(id, now)
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    companion object {
        const val REVOKED_REASON = "device revoked"
        private const val CODE_LENGTH = 8
        private const val CODE_GROUP = 4

        // 100 guesses per window against a 40-bit code that lives 10 minutes is about 1e-9. A low cap would let
        // anyone who can reach /pair lock pairing again at once with a few junk codes.
        private const val MAX_FAILURES = 100
        private const val TOKEN_BYTES = 32
        private const val ID_BYTES = 6
        private const val MS = 1000L
        private const val TOKEN_PREFIX = "rfqd_"
        private const val DEVICE_PREFIX = "dev_"

        // No 0, 1, I or O, so a code read aloud or off a screen is not misread.
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val TOUCH_EVERY = 1.minutes

        /** Upper-cases [code] and drops spaces and hyphens, so `abcd-efgh` and `ABCD EFGH` match. */
        fun normalise(code: String): String = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()

        /** The SHA-256 digest of [value]. Tokens and codes are random, so a plain digest is enough. */
        fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    }
}

/** A [DeviceVerifier] for one shared token and nothing else. Tests use it. */
internal class SingleTokenVerifier(
    token: String?,
) : DeviceVerifier {
    private val digest = token?.let(DeviceService::digest)

    override suspend fun verify(token: String): DeviceCheck =
        if (digest != null && MessageDigest.isEqual(digest, DeviceService.digest(token))) {
            DeviceCheck.Active(BridgeSecurity.LEGACY_DEVICE_ID)
        } else {
            DeviceCheck.Unknown
        }
}
