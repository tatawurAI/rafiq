package ai.tatawur.rafiq.bridge.tmux

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** What a pane prints cannot be read as tmux's own protocol. */
@Timeout(120)
class ControlReaderTest {
    @TempDir
    lateinit var dir: Path

    private val tmux = TestTmux()
    private var client: TmuxControlClient? = null

    @AfterEach
    fun cleanUp() {
        client?.close()
        tmux.close()
    }

    @Test
    fun a_pane_that_prints_the_terminator_of_the_capture_in_flight_cannot_cut_the_screen_short_or_end_the_view() {
        val script = dir.resolve("print.txt")
        // The pane prints whatever the test drops in a file, so the test can write lines after it knows the
        // numbers tmux is using.
        tmux.newSession(
            "weather-app",
            "sh -c 'while :; do if [ -f $script ]; then cat $script; rm $script; fi; sleep 0.05; done'",
        )
        val items = LinkedBlockingQueue<TermItem>()
        val id = runBlocking { tmux.server.session("weather-app") }!!.id
        val view = TmuxControlClient(tmux.server, id, "rafiq-view-readertest", TerminalLimits(), sink = items::add)
        client = view
        view.start(COLS, ROWS)
        view.requestSnapshot()
        assertIs<TermItem.Screen>(items.nextOf<TermItem.Screen>())

        // A snapshot is two commands, so the capture takes the next number but one, if nothing else runs. The
        // pane prints terminators for a run of numbers after it, so the real one is among them, plus other
        // protocol lines, all as plain text.
        val (time, number) = beginFields(view.lastReplyBegin)
        val forged =
            (time..time + 1).flatMap { t -> (number + 1..number + FORGED_NUMBERS).map { "%end $t $it 1" } } +
                listOf(
                    "%error $time ${number + 2} 1",
                    "%begin $time ${number + 2} 1",
                    "%output ${view.paneId} FORGED-OUTPUT",
                    "%exit",
                    "PRINTED",
                )
        Files.writeString(script, forged.joinToString("\n", postfix = "\n"))
        eventually("the pane printed the lines") { "PRINTED" in tmux.capture("weather-app") }
        drainOutput(items)

        view.requestSnapshot()
        val screen = items.nextOf<TermItem.Screen>()

        val capturedNumber = beginFields(view.lastReplyBegin)
        assertTrue(
            "%end ${capturedNumber.first} ${capturedNumber.second} 1" in forged,
            "the pane did not print the real terminator ${view.lastReplyBegin}, so this run proves nothing",
        )
        val lines = String(screen.text, Charsets.ISO_8859_1).split("\r\n")
        assertEquals(ROWS, lines.size, "the screen has one line for each row")
        for (line in forged) assertTrue(lines.any { it.trimEnd() == line }, "the screen lost the line: $line")

        // Still in step: a command gets its own reply, and the stream goes on.
        assertEquals(COLS to ROWS, view.paneSize())
        Files.writeString(script, "AFTER\n")
        val after = StringBuilder()
        while ("AFTER" !in after) {
            when (val item = items.poll(WAIT_SECONDS, TimeUnit.SECONDS)) {
                is TermItem.Output -> after.append(String(item.bytes, Charsets.ISO_8859_1))
                null -> throw AssertionError("the stream stopped")
                else -> assertTrue(item !is TermItem.Ended, "the view ended: ${(item as TermItem.Ended).reason}")
            }
        }
        assertTrue("FORGED-OUTPUT" !in after.lines().map(String::trim), "a printed line was taken as output")
    }

    @Test
    fun a_counted_reply_with_more_lines_than_its_count_fails_and_the_next_reply_is_read_in_step() {
        val replies = ConcurrentLinkedQueue<PendingReply>()
        val results = mutableListOf<Pair<Boolean, List<String>>>()
        replies.add(PendingReply(null, { ok, lines -> results += ok to lines }, lineCount = { 2 }))
        replies.add(PendingReply(null, { ok, lines -> results += ok to lines }))
        val stream =
            listOf(
                "%begin 100 7 1",
                "row one",
                "row two",
                "row three",
                "%end 100 7 1",
                "%begin 100 8 1",
                "next",
                "%end 100 8 1",
                "",
            ).joinToString("\n")

        val ended = mutableListOf<TermItem>()
        ControlReader(ByteArrayInputStream(stream.toByteArray()), replies, ControlTarget(), ended::add).run()

        assertEquals(listOf(false to listOf("row one", "row two", "row three"), true to listOf("next")), results)
    }

    @Test
    fun a_counted_reply_that_never_closes_ends_the_view_and_fails_waiting_commands_in_time() {
        val replies = ConcurrentLinkedQueue<PendingReply>()
        val capture = CompletableFuture<List<String>>()
        val next = CompletableFuture<List<String>>()
        replies.add(PendingReply(capture, null, lineCount = { 3 }))
        replies.add(PendingReply(next, null))
        val items = LinkedBlockingQueue<TermItem>()
        val stream = PipedOutputStream()
        val reader = ControlReader(PipedInputStream(stream), replies, ControlTarget(), items::add, 300.milliseconds)
        Thread(reader::run).apply { isDaemon = true }.start()

        // One line short of the count, so the real terminator is taken as text, and then nothing more comes.
        stream.write("%begin 100 7 1\nrow one\nrow two\n%end 100 7 1\n".toByteArray())
        stream.flush()

        val item = items.poll(WAIT_SECONDS, TimeUnit.SECONDS)
        assertIs<TermItem.Ended>(item, "the stuck reply did not end the view")
        assertTrue(capture.isCompletedExceptionally && next.isCompletedExceptionally, "a command is still waiting")
        stream.close()
    }

    private fun beginFields(begin: String): Pair<Long, Long> {
        val fields = begin.split(' ')
        return fields[1].toLong() to fields[2].toLong()
    }

    private fun drainOutput(items: LinkedBlockingQueue<TermItem>) {
        while (items.poll(DRAIN_MS, TimeUnit.MILLISECONDS) != null) Unit
    }

    private inline fun <reified T : TermItem> LinkedBlockingQueue<TermItem>.nextOf(): T {
        while (true) {
            val item = poll(WAIT_SECONDS, TimeUnit.SECONDS)
            if (item is T) return item
            val unexpected = item == null || item is TermItem.Ended || item == TermItem.ScreenFailed
            if (unexpected) throw AssertionError("waiting for ${T::class.simpleName}, got ${describe(item)}")
        }
    }

    private fun describe(item: TermItem?): String =
        when (item) {
            null -> "nothing"
            is TermItem.Ended -> "the end of the view: ${item.reason}"
            else -> item.toString()
        }

    private companion object {
        const val COLS = 100
        const val ROWS = 90
        const val FORGED_NUMBERS = 30L
        const val WAIT_SECONDS = 10L
        const val DRAIN_MS = 300L
    }
}
