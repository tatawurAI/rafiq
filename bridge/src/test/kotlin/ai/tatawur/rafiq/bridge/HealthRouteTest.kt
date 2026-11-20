package ai.tatawur.rafiq.bridge

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun health_reports_ok_and_the_build_version() =
        testApplication {
            val version = bridgeVersion()
            val fixture = BridgeFixture(dir)
            application { bridgeModule(version, fixture.log, fixture.sessions) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
            assertEquals("""{"status":"ok","version":"$version"}""", response.bodyAsText())
            // Guards the resource filtering: an unexpanded template would report "${version}".
            assertTrue(version.first().isDigit(), "version was '$version'")
            fixture.close()
        }
}
