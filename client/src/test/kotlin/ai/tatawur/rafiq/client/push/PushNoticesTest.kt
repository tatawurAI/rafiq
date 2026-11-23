package ai.tatawur.rafiq.client.push

import ai.tatawur.rafiq.client.sync.BridgeApi
import ai.tatawur.rafiq.client.sync.BridgeCredentials
import ai.tatawur.rafiq.client.sync.HttpRequest
import ai.tatawur.rafiq.client.sync.HttpResponse
import ai.tatawur.rafiq.client.sync.HttpTransport
import ai.tatawur.rafiq.protocol.ApprovalOption
import ai.tatawur.rafiq.protocol.ApprovalOptionKind
import ai.tatawur.rafiq.protocol.EventPayload
import ai.tatawur.rafiq.protocol.Session
import ai.tatawur.rafiq.protocol.api.PushKind
import ai.tatawur.rafiq.protocol.api.PushPayload
import ai.tatawur.rafiq.protocol.api.SessionSummary
import ai.tatawur.rafiq.protocol.protocolJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A push is untrusted and carries ids only (ADR 0010). What the phone shows comes from your server, and only
 * for sessions and approvals your server lists.
 */
class PushNoticesTest {
    private val requests = mutableListOf<HttpRequest>()

    @Test
    fun an_approval_push_shows_the_approval_your_server_lists() =
        runTest {
            val notices = notices(listed(open = listOf(request("apr_1"))))

            val notice = notices.noticeFor(push(PushKind.APPROVAL, "apr_1"), decrypted = true)

            val approval = assertIs<PushNotice.Approval>(notice)
            assertEquals("apr_1", approval.approval.id)
            assertEquals("Fix the login test", approval.approval.detail?.text ?: approval.approval.description)
            assertEquals("weather-app", approval.session.title)
        }

    @Test
    fun a_push_that_did_not_decrypt_or_names_an_unlisted_session_shows_nothing() =
        runTest {
            val notices = notices(listed(open = listOf(request("apr_1"))))

            assertEquals(PushNotice.Nothing, notices.noticeFor(push(PushKind.APPROVAL, "apr_1"), decrypted = false))
            assertTrue(requests.isEmpty(), "an undecrypted push must not even reach your server")
            val forged = push(PushKind.APPROVAL, "apr_1", sessionId = "ses_someone_else")
            assertEquals(PushNotice.Nothing, notices.noticeFor(forged, decrypted = true))
            assertEquals(PushNotice.Nothing, notices.noticeFor("not json".toByteArray(), decrypted = true))
        }

    @Test
    fun when_your_server_cannot_be_reached_the_notification_is_generic() =
        runTest {
            val notices = notices(HttpTransport { throw IOException("Network is unreachable") })

            val notice = notices.noticeFor(push(PushKind.APPROVAL, "apr_1"), decrypted = true)

            assertEquals(PushNotice.Generic(SESSION, PushKind.APPROVAL, "apr_1"), notice)
        }

    @Test
    fun a_cleared_approval_removes_its_notification_and_so_does_one_answered_before_the_fetch() =
        runTest {
            val notices = notices(listed(open = emptyList()))

            assertEquals(
                PushNotice.Clear("apr_1"),
                notices.noticeFor(push(PushKind.APPROVAL_CLEARED, "apr_1"), decrypted = true),
            )
            assertTrue(requests.isEmpty(), "clearing needs nothing from your server")
            assertEquals(PushNotice.Clear("apr_2"), notices.noticeFor(push(PushKind.APPROVAL, "apr_2"), decrypted = true))
        }

    @Test
    fun the_session_open_in_the_foreground_is_not_notified_twice() =
        runTest {
            val notices = notices(listed(open = listOf(request("apr_1"))), showing = SESSION)

            assertEquals(PushNotice.Nothing, notices.noticeFor(push(PushKind.APPROVAL, "apr_1"), decrypted = true))
            assertEquals(PushNotice.Nothing, notices.noticeFor(push(PushKind.TURN_DONE), decrypted = true))
            assertIs<PushNotice.Approval>(
                notices(listed(open = listOf(request("apr_1"))), showing = "ses_other")
                    .noticeFor(push(PushKind.APPROVAL, "apr_1"), decrypted = true),
            )
        }

    @Test
    fun a_turn_done_push_shows_the_session_and_its_last_line() =
        runTest {
            val notices = notices(listed(open = emptyList(), lastLine = "All tests pass."))

            val notice = notices.noticeFor(push(PushKind.TURN_DONE), decrypted = true)

            val done = assertIs<PushNotice.TurnDone>(notice)
            assertEquals("All tests pass.", done.lastLine)
        }

    private fun notices(
        transport: HttpTransport,
        showing: String? = null,
    ) = PushNotices(
        BridgeApi({ request -> requests += request; transport.execute(request) }) {
            BridgeCredentials("https://bridge.example.ts.net", "test-token")
        },
        isShowing = { it == showing },
    )

    private fun listed(
        open: List<EventPayload.ApprovalRequest>,
        lastLine: String? = null,
    ): HttpTransport {
        val session = Session(SESSION, "claude", "/srv/weather-app", "weather-app", createdAtMs = 1, updatedAtMs = 2)
        val body =
            protocolJson.encodeToString(
                ListSerializer(SessionSummary.serializer()),
                listOf(SessionSummary(session, lastSeq = 7, lastLine = lastLine, openApprovals = open)),
            )
        return HttpTransport { HttpResponse(200, body) }
    }

    private fun request(id: String) =
        EventPayload.ApprovalRequest(
            id = id,
            toolCallId = "call_1",
            description = "Fix the login test",
            options = listOf(ApprovalOption("allow", ApprovalOptionKind.ALLOW_ONCE, "Allow")),
        )

    private fun push(
        kind: PushKind,
        approvalId: String? = null,
        sessionId: String = SESSION,
    ): ByteArray =
        protocolJson
            .encodeToString(PushPayload.serializer(), PushPayload(kind = kind, sessionId = sessionId, approvalId = approvalId))
            .toByteArray()

    private companion object {
        const val SESSION = "ses_weather_app"
    }
}
