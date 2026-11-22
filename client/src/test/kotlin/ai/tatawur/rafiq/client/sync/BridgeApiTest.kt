package ai.tatawur.rafiq.client.sync

import ai.tatawur.rafiq.client.CommandResult
import ai.tatawur.rafiq.client.ConnectionState
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Screens branch on the kind of [CommandResult], so each bridge answer must map to the right one. */
class BridgeApiTest {
    @Test
    fun each_bridge_answer_maps_to_the_result_kind_the_screens_branch_on() =
        runTest {
            val cases =
                listOf(
                    answer(401, error("unauthorized")) to CommandResult.Unauthorized,
                    answer(409, error("approval_gone")) to CommandResult.Gone,
                    answer(409, error("turn_in_progress")) to CommandResult.Busy,
                    answer(409, error("something_new")) to CommandResult.Failed("something_new", "Not now."),
                    answer(404, error("not_found")) to CommandResult.NotFound,
                    answer(500, "not json") to CommandResult.Failed("http_500", "The bridge answered with HTTP 500."),
                    HttpTransport { throw IOException("Network is unreachable") } to CommandResult.Offline,
                )
            for ((transport, expected) in cases) {
                val api = BridgeApi(transport) { BridgeCredentials("https://bridge.example.ts.net", "test-token") }
                assertEquals(expected, api.approve("apr_1", "allow_once"), "approve")
                assertEquals(expected, api.prompt("ses_weather_app", "Run the tests."), "prompt")
            }
        }

    @Test
    fun an_approval_refused_with_401_moves_the_connection_to_unauthorized() =
        runTest {
            val repository =
                SyncingSessionRepository(
                    http = answer(401, error("unauthorized")),
                    source = { _, _ -> throw IOException("This test opens no sockets.") },
                    store = InMemoryEventStore(),
                    conditions = FakeConditions(),
                    credentials = pairedWith(),
                    scope = backgroundScope,
                    nowMs = { testScheduler.currentTime },
                    random = Random(seed = 1),
                )

            repository.approve("apr_1", "allow_once")
            testScheduler.runCurrent()

            assertEquals(ConnectionState.Unauthorized, repository.connection.value)
        }

    private fun answer(
        status: Int,
        body: String,
    ) = HttpTransport { HttpResponse(status, body) }

    private fun error(code: String) = """{"error":{"code":"$code","message":"Not now."}}"""
}
