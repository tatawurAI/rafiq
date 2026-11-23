package ai.tatawur.rafiq.bridge.push

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLContext
import kotlin.coroutines.cancellation.CancellationException

/** How hard a push service should try to deliver a message right away (RFC 8030 section 5.3). */
enum class PushUrgency(
    val header: String,
) {
    NORMAL("normal"),
    HIGH("high"),
}

/** What one attempt to hand a push to a push service came to. */
sealed interface SendOutcome {
    /** The push service took it. */
    data object Accepted : SendOutcome

    /** 404 or 410: the subscription is gone for good. The registration should be deleted. */
    data object Gone : SendOutcome

    /** Worth another try later: a timeout, a dropped connection, 429 or a 5xx. */
    data class Retry(
        val reason: String,
    ) : SendOutcome

    /** Refused in a way another try will not fix, such as a redirect or another 4xx. */
    data class Failed(
        val reason: String,
    ) : SendOutcome
}

/**
 * Hands one encrypted push to a push service. Behind an interface so another transport can be added later, and so
 * the notifier's tests need no network.
 */
fun interface PushTransport {
    /**
     * Posts [body] to [endpoint].
     *
     * @param ttlSeconds how long the push service may hold the message while the phone is offline.
     */
    suspend fun send(
        endpoint: String,
        body: ByteArray,
        ttlSeconds: Long,
        urgency: PushUrgency,
    ): SendOutcome
}

/**
 * The [PushTransport] for RFC 8030 push services, on the JDK's HTTP client.
 *
 * The endpoint came from a paired phone, so the bridge treats it with care: `https` only, checked again here; no
 * redirects followed; a short connect and response timeout; the response body is never read. Reasons in a
 * [SendOutcome] name a status code or an exception class and never the endpoint, because the endpoint is a
 * capability and an exception message may hold it.
 */
class HttpPushSender(
    private val client: HttpClient = defaultClient(),
    private val timeout: Duration = TIMEOUT,
) : PushTransport {
    @Suppress("TooGenericExceptionCaught") // Any failure of one attempt is an outcome, never a crash of the notifier.
    override suspend fun send(
        endpoint: String,
        body: ByteArray,
        ttlSeconds: Long,
        urgency: PushUrgency,
    ): SendOutcome {
        if (!StorePushRegistry.isSafeEndpoint(endpoint)) return SendOutcome.Failed("endpoint is not https")
        val request =
            HttpRequest
                .newBuilder(URI(endpoint))
                .timeout(timeout)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", ttlSeconds.toString())
                .header("Urgency", urgency.header)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return try {
            outcome(client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timedOut: HttpTimeoutException) {
            SendOutcome.Retry(timedOut.javaClass.simpleName)
        } catch (failure: IOException) {
            SendOutcome.Retry(failure.javaClass.simpleName)
        } catch (failure: Exception) {
            SendOutcome.Failed(failure.javaClass.simpleName)
        }
    }

    companion object {
        /** Both the connect timeout and the time allowed for the response. */
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        private val SUCCESS = 200..299
        private const val NOT_FOUND = 404
        private const val GONE = 410
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERRORS = 500

        /**
         * The client the bridge uses: no redirects and a short connect timeout. [tls] is the platform's default;
         * tests pass one that trusts their local push service.
         */
        fun defaultClient(tls: SSLContext = SSLContext.getDefault()): HttpClient =
            HttpClient
                .newBuilder()
                .sslContext(tls)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build()

        /** Maps a push service's status code to an outcome. */
        fun outcome(status: Int): SendOutcome =
            when {
                status in SUCCESS -> SendOutcome.Accepted
                status == NOT_FOUND || status == GONE -> SendOutcome.Gone
                status == TOO_MANY_REQUESTS || status >= SERVER_ERRORS -> SendOutcome.Retry("status $status")
                else -> SendOutcome.Failed("status $status")
            }
    }
}
