package ai.tatawur.rafiq.bridge

import ai.tatawur.rafiq.bridge.server.AdminRequest
import ai.tatawur.rafiq.bridge.server.AdminResponse
import ai.tatawur.rafiq.bridge.server.DeviceAdminServer
import ai.tatawur.rafiq.bridge.setup.TerminalQr
import ai.tatawur.rafiq.protocol.api.PairingLink
import ai.tatawur.rafiq.protocol.protocolJson
import java.io.IOException
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The `rafiq-bridge pair`, `devices` and `revoke` commands. They talk to the running bridge over the
 * Unix socket in its data folder, so they need the same `RAFIQ_DATA_DIR` the bridge runs with. `pair` also prints
 * the code as a QR code the app can scan.
 */
object DeviceCli {
    /** The first arguments that mean "run a device command" instead of "start the bridge". */
    val COMMANDS = setOf("pair", "devices", "revoke")

    private const val USAGE =
        "Usage: rafiq-bridge pair --name <device> | rafiq-bridge devices | rafiq-bridge revoke <id>"
    private const val FAILED = 1
    private const val USAGE_ERROR = 2
    private const val PAIR_ARGS = 3
    private val ANSWER_TIMEOUT = 10.seconds
    private const val MS_PER_MINUTE = 60_000L

    /** Runs one command and returns the exit code: 0 done, 1 the bridge refused or is not running, 2 bad usage. */
    fun run(
        args: List<String>,
        environment: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
        answerTimeout: Duration = ANSWER_TIMEOUT,
    ): Int {
        val request = parse(args)
        val dataDir = environment["RAFIQ_DATA_DIR"]?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            request == null -> {
                err.println(USAGE)
                USAGE_ERROR
            }

            dataDir == null -> {
                err.println("RAFIQ_DATA_DIR is not set. Set it to the folder your bridge runs with.")
                FAILED
            }

            else -> {
                val response =
                    try {
                        send(DeviceAdminServer.socketPathFor(Path.of(dataDir)), request, answerTimeout)
                    } catch (failure: IOException) {
                        err.println(
                            "Could not reach the bridge in $dataDir: ${failure.message?.trimEnd('.')}. Is it running?",
                        )
                        return FAILED
                    }
                print(request, response, out, err)
            }
        }
    }

    private fun parse(args: List<String>): AdminRequest? =
        when (args.firstOrNull()) {
            "pair" -> {
                val name = if (args.size == PAIR_ARGS && args[1] == "--name") args[2] else null
                name?.let { AdminRequest("pair", name = it) }
            }

            "devices" -> {
                AdminRequest("devices").takeIf { args.size == 1 }
            }

            "revoke" -> {
                args.getOrNull(1)?.takeIf { args.size == 2 }?.let { AdminRequest("revoke", id = it) }
            }

            else -> {
                null
            }
        }

    private fun print(
        request: AdminRequest,
        response: AdminResponse,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        if (!response.ok) {
            err.println(response.error ?: "The bridge refused.")
            return FAILED
        }
        when (request.op) {
            "pair" -> printPair(response, out)
            "devices" -> printDevices(response, out)
            else -> printRevoke(request, response, out)
        }
        return 0
    }

    private fun printPair(
        response: AdminResponse,
        out: PrintStream,
    ) {
        val minutes = ((response.expiresAtMs ?: 0) - System.currentTimeMillis()) / MS_PER_MINUTE
        out.println("Pairing code for ${response.name}: ${response.code}")
        out.println(
            response.url?.let { "In the app, enter this server address: $it" }
                ?: "In the app, enter your server's https address (see docs/setup.md).",
        )
        out.println("The code works once and expires in about $minutes minutes.")
        val url = response.url
        val code = response.code
        if (url != null && code != null) {
            // The QR holds the address and this one-time code, never a device token.
            out.println("Or scan this with the app:")
            out.print(TerminalQr.render(PairingLink.build(url, code)))
        }
    }

    private fun printDevices(
        response: AdminResponse,
        out: PrintStream,
    ) {
        val devices = response.devices.orEmpty()
        if (devices.isEmpty()) out.println("No devices yet. Run: rafiq-bridge pair --name <device>")
        devices.forEach {
            val state = it.revokedAtMs?.let { at -> "revoked ${Instant.ofEpochMilli(at)}" } ?: "active"
            val seen = it.lastSeenMs?.let { at -> Instant.ofEpochMilli(at).toString() } ?: "never"
            out.println("${it.id}  ${it.name}  last seen $seen  $state")
        }
    }

    private fun printRevoke(
        request: AdminRequest,
        response: AdminResponse,
        out: PrintStream,
    ) {
        out.println(
            if (response.closed == true) {
                "Revoked ${request.id}. Its open connections are closed."
            } else {
                "${request.id} was already revoked."
            },
        )
    }

    private fun send(
        socket: Path,
        request: AdminRequest,
        timeout: Duration,
    ): AdminResponse {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        val timedOut = AtomicBoolean(false)
        // Closing the channel from here is what ends a read that would otherwise wait for ever.
        val watchdog =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "rafiq-cli-deadline").apply { isDaemon = true } }
        watchdog.schedule({
            timedOut.set(true)
            channel.close()
        }, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
            val line = protocolJson.encodeToString(AdminRequest.serializer(), request) + "\n"
            channel.write(ByteBuffer.wrap(line.toByteArray()))
            val answer = DeviceAdminServer.readLine(channel) ?: throw IOException("The bridge closed the connection.")
            return protocolJson.decodeFromString(AdminResponse.serializer(), answer)
        } catch (failure: IOException) {
            throw if (timedOut.get()) IOException("The bridge did not answer within $timeout.", failure) else failure
        } finally {
            watchdog.shutdownNow()
            channel.close()
        }
    }
}
