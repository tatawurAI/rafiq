package ai.tatawur.rafiq.terminal

import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.client.sync.HandshakeRejectedException
import ai.tatawur.rafiq.client.terminal.TerminalFrame
import ai.tatawur.rafiq.client.terminal.TerminalPort
import ai.tatawur.rafiq.client.terminal.TerminalUplink
import ai.tatawur.rafiq.protocol.api.TERMINAL_WS_PROTOCOL
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
 */
class OkHttpTerminalPort(
    private val client: OkHttpClient,
    private val credentials: StateFlow<BridgeCredentials?>,
) : TerminalPort {
    override fun open(sessionId: String): Flow<TerminalFrame> =
        callbackFlow {
            val paired = credentials.value ?: throw IOException("This phone is not paired.")
            val url =
                paired.baseUrl.replaceFirst("https://", "wss://") + "/terminal?session=" + Uri.encode(sessionId)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Sec-WebSocket-Protocol", "$TERMINAL_WS_PROTOCOL, ${paired.token}")
                    .build()
            val socket = client.newWebSocket(request, Listener(this))
            awaitClose { socket.close(NORMAL_CLOSURE, null) }
        }
            // Pane bytes must never be dropped: a lost frame would leave the screen wrong.
            .buffer(Channel.UNLIMITED)

    private class Listener(
        private val scope: ProducerScope<TerminalFrame>,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            scope.trySend(TerminalFrame.Opened(SocketUplink(webSocket)))
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            scope.trySend(TerminalFrame.Text(text))
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            scope.trySend(TerminalFrame.Binary(bytes.toByteArray()))
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
            scope.channel.close()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            val status = response?.code
            scope.channel.close(
                when {
                    status != null && status != SWITCHING_PROTOCOLS -> HandshakeRejectedException(status)
                    t is IOException -> t
                    else -> IOException("The terminal connection failed.", t)
                },
            )
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
