package ai.tatawur.rafiq.bridge.tmux.reader

import ai.tatawur.rafiq.bridge.tmux.TmuxServer
import ai.tatawur.rafiq.bridge.tmux.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** The tmux commands behind typing, against a stand-in tmux that records what it is asked. */
@Timeout(60)
class TmuxPaneIoTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cleanUp() = scope.cancel()

    @Test
    fun the_paste_buffer_is_deleted_even_when_the_send_is_cancelled_mid_paste() {
        val calls = dir.resolve("calls.txt")
        val tmux = dir.resolve("tmux")
        // Reads what load-buffer is given, records every command, and takes its time over paste-buffer.
        Files.writeString(
            tmux,
            "#!/bin/sh\ncase \"$1\" in load-buffer) cat > /dev/null ;; esac\necho \"$1\" >> '$calls'\n" +
                "case \"$1\" in paste-buffer) sleep 2 ;; esac\nexit 0\n",
        )
        tmux.toFile().setExecutable(true)
        val io = TmuxPaneIo(TmuxServer(binary = tmux.toString()))

        val send = scope.launch { io.paste("%3", "secret text") }
        eventually("the paste started") { Files.exists(calls) && "paste-buffer" in Files.readString(calls) }
        send.cancel()

        eventually("the buffer was deleted after the cancel") { "delete-buffer" in Files.readString(calls) }
    }
}
