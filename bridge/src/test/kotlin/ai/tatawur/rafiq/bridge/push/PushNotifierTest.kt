package ai.tatawur.rafiq.bridge.push

import ai.tatawur.rafiq.bridge.FakeAdapter
import ai.tatawur.rafiq.bridge.TEST_MAIN_THREAD
import ai.tatawur.rafiq.bridge.TEST_SESSION
import ai.tatawur.rafiq.bridge.seed
import ai.tatawur.rafiq.bridge.server.DeviceService
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.server.RedeemResult
import ai.tatawur.rafiq.bridge.server.TestClock
import ai.tatawur.rafiq.bridge.sessions.SessionManager
import ai.tatawur.rafiq.bridge.status
import ai.tatawur.rafiq.bridge.store.DeviceStore
import ai.tatawur.rafiq.bridge.store.EventLog
import ai.tatawur.rafiq.bridge.store.Subscription
import ai.tatawur.rafiq.protocol.AgentState
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.Event
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.PushKind
import ai.tatawur.rafiq.protocol.api.PushPayload
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Timeout(60)
class PushNotifierTest {
    /** What the fake push service received, decrypted with the phone's key. */
    private data class Received(
        val endpoint: String,
        val payload: PushPayload,
        val urgency: PushUrgency,
    )

    /** One test's world on virtual time: the log, the device store, two phones and a scripted push service. */
    private inner class World(
        val test: TestScope,
        visible: (String) -> Boolean = { true },
        settings: PushSettings = PushSettings(),
        subscribe: (suspend (EventLog, Int) -> Subscription)? = null,
    ) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val log = EventLog("jdbc:sqlite::memory:", dispatcher)
        val store = DeviceStore("jdbc:sqlite::memory:", dispatcher)
        val devices = DeviceService(store, InMemoryConnectionRegistry(), TestClock())
        val phones = HashMap<String, TestPhoneKeys>()
        val received = mutableListOf<Received>()
        val attempts = mutableListOf<String>()

        /** The answer for each attempt, in order. Once empty, every attempt is accepted. */
        val script = ArrayDeque<SendOutcome>()

        private val transport =
            PushTransport { endpoint, body, _, urgency ->
                attempts += endpoint
                val outcome = script.removeFirstOrNull() ?: SendOutcome.Accepted
                if (outcome == SendOutcome.Accepted) {
                    val phone = phones.getValue(endpoint)
                    val payload =
                        protocolJson.decodeFromString(
                            PushPayload.serializer(),
                            phone.decrypt(body).decodeToString(),
                        )
                    received += Received(endpoint, payload, urgency)
                }
                outcome
            }

        val notifier =
            PushNotifier(
                subscribe = { capacity -> subscribe?.invoke(log, capacity) ?: log.subscribeAll(capacity) },
                threadsOf = log::threads,
                store = store,
                visible = visible,
                transport = transport,
                scope = test.backgroundScope,
                settings = settings,
                dispatcher = dispatcher,
                nowMs = { test.testScheduler.currentTime },
            )

        /** Pairs a device and, unless [register] is false, registers it for pushes. Returns its endpoint. */
        suspend fun phone(
            name: String,
            register: Boolean = true,
        ): String {
            val code = devices.issueCode(name).code
            val id = (devices.redeem(code) as RedeemResult.Paired).response.deviceId
            val endpoint = "https://push.example.ts.net/up/$name"
            val keys = TestPhoneKeys()
            phones[endpoint] = keys
            if (register) StorePushRegistry(store).register(id, keys.registration(endpoint))
            return endpoint
        }

        fun kinds() = received.map { it.payload.kind }
    }

    private fun approval(id: String) =
        EventPayload.ApprovalRequest(
            id = id,
            toolCallId = "tool_$id",
            description = "rm -rf /work/weather-app/build",
            options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow")),
        )

    @Test
    fun an_approval_reaches_every_registered_phone_and_its_clearing_follows() =
        runTest {
            val world = World(this)
            val phone = world.phone("phone")
            val tablet = world.phone("tablet")
            world.phone("watch", register = false)
            world.log.seed()
            world.notifier.start()

            world.log.append(TEST_SESSION.id, approval("apr_1"))
            world.log.append(TEST_SESSION.id, EventPayload.ApprovalResolved("apr_1"))
            runCurrent()

            assertEquals(setOf(phone, tablet), world.received.map { it.endpoint }.toSet())
            for (endpoint in listOf(phone, tablet)) {
                val mine = world.received.filter { it.endpoint == endpoint }
                assertEquals(
                    listOf(
                        PushPayload(kind = PushKind.APPROVAL, sessionId = TEST_SESSION.id, approvalId = "apr_1"),
                        PushPayload(
                            kind = PushKind.APPROVAL_CLEARED,
                            sessionId = TEST_SESSION.id,
                            approvalId = "apr_1",
                        ),
                    ),
                    mine.map { it.payload },
                )
                assertEquals(PushUrgency.HIGH, mine.first().urgency)
            }
        }

    @Test
    fun turn_done_waits_out_the_debounce_and_a_quick_restart_cancels_it() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            val main = TEST_MAIN_THREAD.id

            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, main))
            world.log.append(TEST_SESSION.id, status(AgentState.WAITING_ON_YOU, main))
            advanceTimeBy(1.seconds)
            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, main))
            advanceTimeBy(10.seconds)
            assertEquals(emptyList(), world.kinds())

            world.log.append(TEST_SESSION.id, status(AgentState.WAITING_ON_YOU, main))
            advanceTimeBy(2.seconds)
            assertEquals(emptyList(), world.kinds())
            advanceTimeBy(2.seconds)
            assertEquals(listOf(PushKind.TURN_DONE), world.kinds())
        }

    @Test
    fun a_main_thread_waiting_on_an_approval_is_not_a_finished_turn() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            val main = TEST_MAIN_THREAD.id

            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, main))
            // The ACP adapter marks the thread waiting just before it logs the approval.
            world.log.append(TEST_SESSION.id, status(AgentState.WAITING_ON_YOU, main))
            world.log.append(TEST_SESSION.id, approval("apr_1"))
            advanceTimeBy(1.minutes)

            assertEquals(listOf(PushKind.APPROVAL), world.kinds())
        }

    @Test
    fun the_events_a_restart_writes_about_the_past_push_nothing() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            // Before the restart: the agent was working and waiting on an approval.
            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, TEST_MAIN_THREAD.id))
            world.log.append(TEST_SESSION.id, approval("apr_old"))
            world.log.append(TEST_SESSION.id, status(AgentState.WAITING_ON_YOU, TEST_MAIN_THREAD.id))
            val before =
                world.log
                    .latest(TEST_SESSION.id, 1)
                    .single()
                    .seq

            // Start-up as Main does it: the notifier subscribes, then recovery runs.
            world.notifier.start()
            SessionManager(world.log, listOf(FakeAdapter()), backgroundScope).recoverAfterRestart()
            advanceTimeBy(1.minutes)

            val written = world.log.after(TEST_SESSION.id, before, 100).map { it.payload }
            assertTrue(written.any { it is EventPayload.ApprovalResolved }, "recovery should cancel the approval")
            assertTrue(written.any { it is EventPayload.StatusChanged && it.state == AgentState.ERROR })
            assertEquals(emptyList(), world.attempts)
        }

    @Test
    fun a_thread_error_after_live_work_pushes_once_though_the_session_reports_it_too() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()

            val main = TEST_MAIN_THREAD.id
            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, main))
            world.log.append(TEST_SESSION.id, status(AgentState.WORKING))
            world.log.append(TEST_SESSION.id, status(AgentState.ERROR, main))
            world.log.append(TEST_SESSION.id, status(AgentState.ERROR))
            runCurrent()
            assertEquals(listOf(PushKind.ERROR), world.kinds())

            // A new turn that fails again is a new push.
            world.log.append(TEST_SESSION.id, status(AgentState.WORKING, main))
            world.log.append(TEST_SESSION.id, status(AgentState.ERROR, main))
            runCurrent()
            assertEquals(listOf(PushKind.ERROR, PushKind.ERROR), world.kinds())
        }

    @Test
    fun a_session_the_bridge_does_not_list_pushes_nothing() =
        runTest {
            val world = World(this, visible = { false })
            world.phone("phone")
            world.log.seed()
            world.notifier.start()

            world.log.append(TEST_SESSION.id, approval("apr_1"))
            runCurrent()

            assertEquals(emptyList(), world.attempts)
        }

    @Test
    fun a_gone_answer_deletes_the_registration_and_later_pushes_skip_the_phone() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            world.script += SendOutcome.Gone

            world.log.append(TEST_SESSION.id, approval("apr_1"))
            runCurrent()
            world.log.append(TEST_SESSION.id, approval("apr_2"))
            runCurrent()

            assertEquals(1, world.attempts.size)
            assertEquals(emptyList(), world.store.pushDevices())
        }

    @Test
    fun a_failing_push_is_tried_a_bounded_number_of_times_and_the_next_one_still_goes() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            repeat(10) { world.script += SendOutcome.Retry("status 503") }

            world.log.append(TEST_SESSION.id, approval("apr_1"))
            advanceTimeBy(1.minutes)
            assertEquals(PushSettings().attempts, world.attempts.size)

            world.script.clear()
            world.log.append(TEST_SESSION.id, approval("apr_2"))
            runCurrent()
            assertEquals(listOf("apr_2"), world.received.map { it.payload.approvalId })
        }

    @Test
    fun pushes_past_the_rate_cap_are_dropped_until_the_window_moves_on() =
        runTest {
            val settings = PushSettings(ratePerDevice = 3, rateWindow = 1.minutes)
            val world = World(this, settings = settings)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()

            repeat(5) { world.log.append(TEST_SESSION.id, approval("apr_$it")) }
            runCurrent()
            assertEquals(listOf("apr_0", "apr_1", "apr_2"), world.received.map { it.payload.approvalId })

            advanceTimeBy(1.minutes)
            world.log.append(TEST_SESSION.id, approval("apr_late"))
            runCurrent()
            assertEquals(
                "apr_late",
                world.received
                    .last()
                    .payload.approvalId,
            )
        }

    @Test
    fun an_unregistered_phone_gets_nothing_already_queued_for_it() =
        runTest {
            val world = World(this)
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            world.script += SendOutcome.Retry("status 503")

            world.log.append(TEST_SESSION.id, approval("apr_1"))
            runCurrent()
            world.log.append(TEST_SESSION.id, approval("apr_2"))
            runCurrent()
            // apr_1 waits out its backoff, apr_2 is queued behind it. The phone unregisters meanwhile.
            world.store.pushDevices().forEach { world.store.deletePush(it) }
            advanceTimeBy(1.minutes)

            assertEquals(1, world.attempts.size)
            assertNull(world.received.firstOrNull())
        }

    @Test
    fun a_notifier_that_falls_behind_resubscribes_and_never_pushes_what_it_missed() =
        runTest {
            // The first feed is one the test overflows by hand, as the log does when a reader is too slow.
            val first = Subscription(Channel(2)) {}
            var opened = 0
            val world =
                World(this, subscribe = { log, capacity -> if (opened++ == 0) first else log.subscribeAll(capacity) })
            world.phone("phone")
            world.log.seed()
            world.notifier.start()
            runCurrent()

            fun missed(id: String) = Event(TEST_SESSION.id, 100, 0, approval(id))
            // The notifier waits on the feed, so it takes the first at once, two more fill the buffer, and the fourth
            // overflows it: the log closes the feed and drops that one.
            repeat(4) { first.offer(missed("apr_missed_$it")) }
            runCurrent()
            world.log.append(TEST_SESSION.id, approval("apr_new"))
            runCurrent()

            assertEquals(2, opened)
            assertEquals(
                listOf("apr_missed_0", "apr_missed_1", "apr_missed_2", "apr_new"),
                world.received.map { it.payload.approvalId },
            )
        }
}
