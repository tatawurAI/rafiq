package ai.tatawur.rafiq.bridge.server

import ai.tatawur.rafiq.bridge.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration

/** A clock the test moves by hand. */
class TestClock(
    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plusMillis(by.inWholeMilliseconds)
    }
}

/** A device service over a real SQLite file in [dir], or in memory when [dir] is null. */
class DeviceFixture(
    dir: Path? = null,
    val clock: TestClock = TestClock(),
    busyTimeoutMs: Int = 5000,
) : AutoCloseable {
    val store = DeviceStore.open(dir, Dispatchers.IO.limitedParallelism(1), busyTimeoutMs)
    val registry = InMemoryConnectionRegistry()
    val service = DeviceService(store, registry, clock)

    /** Pairs a device and returns its id and token. */
    suspend fun pair(name: String = "phone"): Pair<String, String> {
        val code = service.issueCode(name).code
        val paired = (service.redeem(code) as RedeemResult.Paired).response
        return paired.deviceId to paired.token
    }

    override fun close() = store.close()
}
