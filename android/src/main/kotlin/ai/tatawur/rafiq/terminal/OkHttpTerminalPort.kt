package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.client.sync.HandshakeRejectedException
import ai.tatawur.rafiq.client.terminal.InboundFrames
import ai.tatawur.rafiq.client.terminal.TerminalFrame
import ai.tatawur.rafiq.client.terminal.TerminalOverflowException
import ai.tatawur.rafiq.client.terminal.TerminalPort
import ai.tatawur.rafiq.client.terminal.TerminalUplink
import ai.tatawur.rafiq.data.errorCode
import ai.tatawur.rafiq.protocol.api.TERMINAL_WS_PROTOCOL
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException

/**
 * [TerminalPort] over OkHttp's WebSocket, on the paired bridge with the same host and token as
 * `WS /events`. The token rides as the second `Sec-WebSocket-Protocol` value, never in the URL.
 * Received frames wait in a bounded [InboundFrames]. When it is full the socket closes with a
 * [TerminalOverflowException], and the link opens a fresh one.
 */
class OkHttpTerminalPort(
    private val client: OkHttpClient,
    private val credentials: StateFlow<BridgeCredentials?>,
) : TerminalPort {
    override fun open(sessionId: String): Flow<TerminalFrame> =
        flow {
            val paired = credentials.value ?: throw IOException("This phone is not paired.")
            val url =
                paired.baseUrl.replaceFirst("https://", "wss://") + "/terminal?session=" + Uri.encode(sessionId)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Sec-WebSocket-Protocol", "$TERMINAL_WS_PROTOCOL, ${paired.token}")
                    .build()
            val queue = InboundFrames()
            val socket = client.newWebSocket(request, Listener(queue))
            try {
                emitAll(queue.frames())
            } finally {
                socket.close(NORMAL_CLOSURE, null)
            }
        }

    private class Listener(
        private val queue: InboundFrames,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            offer(webSocket, TerminalFrame.Opened(SocketUplink(webSocket)))
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            offer(webSocket, TerminalFrame.Text(text))
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            offer(webSocket, TerminalFrame.Binary(bytes.toByteArray()))
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
            queue.close()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            val status = response?.code
            queue.close(
                when {
                    status != null && status != SWITCHING_PROTOCOLS -> {
                        HandshakeRejectedException(
                            status,
                            response.errorCode(),
                        )
                    }

                    t is IOException -> {
                        t
                    }

                    else -> {
                        IOException("The terminal connection failed.", t)
                    }
                },
            )
        }

        private fun offer(
            webSocket: WebSocket,
            frame: TerminalFrame,
        ) {
            if (!queue.offer(frame)) {
                queue.close(TerminalOverflowException())
                webSocket.cancel()
            }
        }
    }

    private class SocketUplink(
        private val socket: WebSocket,
    ) : TerminalUplink {
        override fun sendBinary(bytes: ByteArray) {
            socket.send(bytes.toByteString())
        }

        override fun sendText(text: String) {
            socket.send(text)
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val SWITCHING_PROTOCOLS = 101
    }
}
