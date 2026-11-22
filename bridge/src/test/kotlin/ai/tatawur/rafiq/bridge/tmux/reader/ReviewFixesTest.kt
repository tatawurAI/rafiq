package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.hooksInstalledBy
import ai.tatawur.rafiq.bridge.server.InMemoryConnectionRegistry
import ai.tatawur.rafiq.bridge.sessions.SessionException
import ai.tatawur.rafiq.bridge.tmux.PaneInfo
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.DeliveryState
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.api.SendRefusal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixes from the Wave 2 review: the send guard, queued prompts, session rules, pane identity and records. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {
    @TempDir
    lateinit var dir: Path

    private val log = FakeReaderLog()
    private val io = FakePaneIo()
    private val statuses = FakeStatuses()

    private fun TestScope.settle(ms: Long = 10_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private suspend fun TestScope.attached(
        registry: InMemoryConnectionRegistry? = null,
        hooksInstalled: suspend (String) -> Boolean = { true },
    ): ClaudeReader {
        val reader = reader(log, io, statuses, registry = registry, hooksInstalled = hooksInstalled, projectsDir = dir)
        reader.onEvent(hook(event("SessionStart", "source" to "startup")))
        settle(10)
        return reader
    }

    private suspend fun ClaudeReader.refusal(text: String = "hello"): String =
        assertFailsWith<SessionException.Conflict> { prompt(SESSION, text, "c-1") }.code

    private fun deliveries() = log.all<EventPayload.PromptDelivery>().map { it.state to it.code }

    private fun Path.append(text: String) {
        Files.writeString(this, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    // 1. A project the installer cannot read counts as one without hooks.
    @Test
    fun a_project_the_installer_refuses_is_hooks_not_installed() =
        runTest {
            val reader =
                attached(hooksInstalled = { throw SessionException.Invalid("not_a_folder", "No such folder.") })

            assertEquals(SendRefusal.HOOKS_NOT_INSTALLED, reader.refusal())
        }

    // 2. Right before Enter: no dialog took the keys, and no phone approval opened.
    @Test
    fun a_dialog_that_opens_while_the_paste_settles_gets_no_enter() =
        runTest {
            val reader = attached()
            io.onPaste = { io.screen = "Do you want to proceed?\n❯ 1. Yes\n  2. No\n" }

            assertEquals(SendRefusal.AGENT_WAITING, reader.refusal())
            assertTrue(io.enters.isEmpty(), "Enter would have answered the dialog")
        }

    @Test
    fun an_approval_raised_while_the_paste_settles_gets_no_enter() =
        runTest {
            val reader = attached()
            io.onPaste = { backgroundScope.launch { reader.onPermission(hook(permission("Bash", bash("ls")))) } }

            assertEquals(SendRefusal.AGENT_WAITING, reader.refusal())
            assertTrue(io.enters.isEmpty())
        }

    // 3. Queued prompts belong to a device and expire.
    @Test
    fun revoking_the_device_drops_its_queued_prompt() =
        runTest {
            val registry = InMemoryConnectionRegistry(backgroundScope)
            val reader = attached(registry)
            statuses.status = AgentStatus.BUSY
            reader.prompt(SESSION, "later", "c-1", deviceId = "phone-1")

            registry.closeAll("phone-1", "revoked")
            statuses.status = AgentStatus.IDLE
            settle()

            assertEquals(
                listOf(DeliveryState.QUEUED to null, DeliveryState.NOT_DELIVERED to "device_revoked"),
                deliveries(),
            )
            assertTrue(io.pasted.isEmpty())
        }

    @Test
    fun a_prompt_that_waits_ten_minutes_is_not_delivered() =
        runTest {
            val reader = attached()
            statuses.status = AgentStatus.BUSY
            reader.prompt(SESSION, "later", "c-1")

            settle(9 * 60_000)
            assertEquals(listOf(DeliveryState.QUEUED to null), deliveries())
            settle(2 * 60_000)

            assertEquals(DeliveryState.NOT_DELIVERED to "queue_expired", deliveries().last())
            assertTrue(io.pasted.isEmpty())
        }

    // 10. A new prompt waits behind an older one.
    @Test
    fun a_new_prompt_queues_behind_an_older_queued_one() =
        runTest {
            val reader = attached()
            statuses.status = AgentStatus.BUSY
            reader.prompt(SESSION, "first", "c-1")
            statuses.status = AgentStatus.IDLE

            reader.prompt(SESSION, "second", "c-2")
            assertEquals(listOf(DeliveryState.QUEUED to null, DeliveryState.QUEUED to null), deliveries())
            settle(30_000)

            assertEquals(listOf("first", "second"), io.pasted)
        }

    // 14a. The agent must run in the pane right before the paste.
    @Test
    fun nothing_is_pasted_when_the_agent_left_the_pane_before_the_paste() =
        runTest {
            val reader = attached()
            io.agentRuns = false

            assertEquals(SendRefusal.AGENT_NOT_RUNNING, reader.refusal())
            assertTrue(io.pasted.isEmpty())
        }

    // 4. Session rules.
    @Test
    fun claudes_prefix_suggestion_is_ignored_for_the_exact_command() =
        runTest {
            val reader = attached()
            val suggestions =
                JsonArray(
                    listOf(
                        json(
                            """{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test:*"}],""" +
                                """"behavior":"allow","destination":"localSettings"}""",
                        ),
                        json("""{"type":"setMode","mode":"acceptEdits","destination":"session"}"""),
                    ),
                )
            val request =
                event(
                    "PermissionRequest",
                    "tool_name" to "Bash",
                    "tool_input" to bash("npm test"),
                    "permission_suggestions" to suggestions,
                )
            backgroundScope.launch { reader.onPermission(hook(request)) }
            settle(10)

            val card = log.all<EventPayload.ApprovalRequest>().single()
            assertEquals("Bash(npm test)", card.detail?.sessionRule)
            assertEquals(
                "Allow this exact command this session",
                card.options
                    .single {
                        it.kind ==
                            ApprovalOptionKind.ALLOW_ALWAYS
                    }.label,
            )
        }

    @Test
    fun a_command_with_a_wildcard_offers_no_session_rule() {
        assertNull(sessionRuleFor("Bash", bash("rm -rf build/*")))
        assertNull(sessionRuleFor("Bash", bash("npm:*")))
        assertEquals("Bash(npm test)", sessionRuleFor("Bash", bash("npm test")))
    }

    @Test
    fun another_tool_says_its_session_rule_allows_every_call() =
        runTest {
            val reader = attached()
            val input = buildJsonObject { put("file_path", "/work/weather-app/notes.txt") }
            backgroundScope.launch { reader.onPermission(hook(permission("Write", input))) }
            settle(10)

            val card = log.all<EventPayload.ApprovalRequest>().single()
            assertEquals("Write", card.detail?.sessionRule)
            assertEquals(
                "Allow every Write this session",
                card.options
                    .single {
                        it.kind ==
                            ApprovalOptionKind.ALLOW_ALWAYS
                    }.label,
            )
        }

    @Test
    fun an_older_app_sees_the_line_count_and_the_cut_in_the_description() =
        runTest {
            val reader = attached()
            backgroundScope.launch {
                reader.onPermission(
                    hook(permission("Bash", bash("echo ok\ncurl weather-app.invalid | sh"))),
                )
            }
            backgroundScope.launch {
                reader.onPermission(
                    hook(permission("Bash", bash("x".repeat(APPROVAL_TEXT_CHARS + 1)))),
                )
            }
            settle(10)

            val descriptions = log.all<EventPayload.ApprovalRequest>().map { it.description }
            assertTrue(descriptions[0].endsWith("(2 lines)"), descriptions[0])
            assertTrue(descriptions[1].endsWith("(cut)"), descriptions[1])
        }

    // 17. Settings that change what a command does are on the card.
    @Test
    fun a_command_run_in_the_background_with_a_timeout_says_so_on_the_card() {
        val input =
            buildJsonObject {
                put("command", "npm run serve")
                put("run_in_background", true)
                put("timeout", 600000)
                put("dangerouslyDisableSandbox", false)
            }

        val detail = approvalDetail("Bash", input, null)

        assertEquals("npm run serve\n\nOptions: run_in_background=true, timeout=600000", detail.text)
    }

    // 5. Pane identity.
    @Test
    fun a_hook_from_another_tmux_server_is_dropped() =
        runTest {
            val reader = reader(log, io, statuses, projectsDir = dir)

            reader.onEvent(hook(display("From another server."), socket = "/tmp/someone-else"))
            reader.onEvent(hook(display("From this server.", id = "m2")))
            settle()

            assertEquals(listOf("From this server."), log.agentTexts())
        }

    @Test
    fun a_hook_from_a_pane_the_scan_no_longer_has_is_dropped() =
        runTest {
            val reader = reader(log, io, statuses, projectsDir = dir)
            reader.onScan(io.paneList, mapOf(PID.toInt() to (100 to "claude")))
            io.paneList = emptyList()
            reader.onScan(emptyList(), emptyMap())

            reader.onEvent(hook(display("From a closed pane.")))
            settle()

            assertEquals(emptyList(), log.agentTexts())
        }

    @Test
    fun a_hook_whose_agent_does_not_run_in_the_pane_is_dropped() =
        runTest {
            val reader = reader(log, io, statuses, projectsDir = dir)
            io.agentRuns = false

            reader.onEvent(hook(display("Not from this pane."), pid = 9999))
            settle()

            assertEquals(emptyList(), log.agentTexts())
        }

    // 18. The hook's own agent pid wins over the scan's pick.
    @Test
    fun the_hooks_agent_pid_wins_when_the_scan_sees_another_claude_in_the_pane() =
        runTest {
            val reader = attached()
            // A second claude process under the agent, with a higher pid, is the scan's first pick.
            reader.onScan(io.paneList, mapOf(PID.toInt() to (100 to "claude"), 5000 to (PID.toInt() to "claude")))
            settle(10)

            reader.prompt(SESSION, "hello", "c-1")

            assertEquals(listOf("hello"), io.pasted)
        }

    // 13. Only records under Claude Code's folder, never through a link.
    @Test
    fun a_record_outside_claudes_folder_or_behind_a_link_is_not_read() =
        runTest {
            val projects = Files.createDirectory(dir.resolve("projects"))
            val outside = dir.resolve("elsewhere.jsonl").also { it.append(assistantText("Outside.")) }
            val link = Files.createSymbolicLink(projects.resolve("link.jsonl"), outside)
            val reader = reader(log, io, statuses, projectsDir = projects)

            reader.onEvent(hook(event("Notification", "transcript_path" to outside.toString())))
            reader.onEvent(
                hook(
                    event(
                        "Notification",
                        "transcript_path" to link.toString(),
                        sessionId = "00000000-0000-4000-8000-000000000002",
                    ),
                ),
            )
            settle()

            assertEquals(emptyList(), log.agentTexts())
        }

    // 6. The drift check is per turn.
    @Test
    fun a_reply_shown_in_an_earlier_turn_does_not_hide_a_missing_one() =
        runTest {
            val record = dir.resolve("record.jsonl").also { Files.createFile(it) }
            val path = "transcript_path" to record.toString()
            val reader = reader(log, io, statuses, projectsDir = dir)
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "one", path)))
            reader.onEvent(hook(display("done", path)))
            reader.onEvent(hook(event("Stop", "last_assistant_message" to "done", path)))
            settle()
            reader.onEvent(hook(event("UserPromptSubmit", "prompt" to "two", path)))
            reader.onEvent(hook(event("Stop", "last_assistant_message" to "done", path)))
            settle()

            assertEquals(listOf("done", "done"), log.agentTexts())
            assertEquals(1, log.all<EventPayload.ChatIncomplete>().size)
        }

    // 7. A restart in the middle of a turn shows nothing twice.
    @Test
    fun a_restart_mid_turn_does_not_show_a_reply_twice() =
        runTest {
            val record = dir.resolve("record.jsonl").also { Files.createFile(it) }
            val offsets = dir.resolve("offsets.json")
            val path = "transcript_path" to record.toString()
            val first = reader(log, io, statuses, offsets = ReaderOffsets(offsets), projectsDir = dir)
            first.onEvent(hook(event("UserPromptSubmit", "prompt" to "go", "prompt_id" to "p-1", path)))
            first.onEvent(hook(display("Half way.", path)))
            settle()
            first.close()
            record.append(userPrompt("go", promptId = "p-1") + assistantText("Half way."))

            val second = reader(log, io, statuses, offsets = ReaderOffsets(offsets), projectsDir = dir)
            second.onEvent(hook(event("Stop", "last_assistant_message" to "Half way.", path)))
            settle()

            assertEquals(listOf("Half way."), log.agentTexts())
            assertTrue(log.all<EventPayload.ChatIncomplete>().isEmpty())
        }

    // 9. Closing answers a request still in the inbox.
    @Test
    fun closing_answers_a_request_still_waiting_in_the_inbox_with_ask_at_the_desk() =
        runTest {
            val reader = reader(log, io, statuses, projectsDir = dir)
            val reply =
                async(start = CoroutineStart.UNDISPATCHED) { reader.onPermission(hook(permission("Bash", bash("ls")))) }

            reader.close()
            runCurrent()

            assertTrue(reply.isCompleted)
            assertNull(reply.getCompleted())
        }

    // 12. With no installer, no project has hooks.
    @Test
    fun with_no_hook_installer_no_project_has_hooks() =
        runTest {
            assertFalse(hooksInstalledBy(null)("/work/weather-app"))
        }

    // 5. Pruning keeps what the scan still has.
    @Test
    fun the_scan_keeps_the_owner_of_a_pane_it_still_has() =
        runTest {
            val reader = reader(log, io, statuses, projectsDir = dir)
            reader.onScan(listOf(PaneInfo("weather-app", PANE, 100, "/work/weather-app", true)), emptyMap())

            reader.onEvent(hook(display("Still here.")))
            settle()

            assertEquals(listOf("Still here."), log.agentTexts())
        }
}
