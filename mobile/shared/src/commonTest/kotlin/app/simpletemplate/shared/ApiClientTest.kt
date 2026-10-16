package app.simpletemplate.shared

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiClientTest {
    @Test
    fun healthParsesServerResponse() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://example.test/health", request.url.toString())
            respond(
                content = """{"status":"ok","version":"0.1.0","environment":"test","extra":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val health = ApiClient("https://example.test/", engine).health()
        assertEquals(HealthStatus("ok", "0.1.0", "test"), health)
    }
}
