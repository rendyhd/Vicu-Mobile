package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.remote.api.LoginRequestDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Vikunja lists a session under the User-Agent of its sign-in, so it names the app and the device. */
class VikunjaUserAgentTest {

    private val agent = "Vicu/2.5.1 (Android; Google Pixel 10 Pro)"

    @Test
    fun `the agent names the app version - the platform and the device`() {
        assertEquals(agent, VikunjaUserAgent.build("2.5.1", "Android", "Google Pixel 10 Pro"))
        assertEquals("Vicu/2.5.1-debug (iOS; iPhone)", VikunjaUserAgent.build("2.5.1-debug", "iOS", "iPhone"))
    }

    @Test
    fun `characters a header or a comment cannot hold are left out`() {
        assertEquals(
            "Vicu/2.5.1 (iPadOS; Rendys iPad 2)",
            VikunjaUserAgent.build("2.5.1", "iPadOS", "Rendy’s  iPad (2);\n"),
        )
        assertEquals("Vicu/2.5.1 (Android)", VikunjaUserAgent.build("2.5 .1/", "Android", "\\()"))
    }

    @Test
    fun `an unknown version or device leaves only what is known`() {
        assertEquals("Vicu (Android)", VikunjaUserAgent.build("", "Android", " "))
        assertEquals("Vicu", VikunjaUserAgent.build("", "", ""))
    }

    @Test
    fun `a long device name is cut short`() {
        val built = VikunjaUserAgent.build("2.5.1", "Android", "x".repeat(200))
        assertEquals("Vicu/2.5.1 (Android; ${"x".repeat(64)})", built)
    }

    @Test
    fun `the sign-in request carries the agent`() = runTest {
        val seen = Mutex()
        val agents = mutableListOf<String?>()
        val h = LoginHarness(signedIn = false, userAgent = agent) { request ->
            seen.withLock { agents += request.headers[HttpHeaders.UserAgent] }
            respond("""{"token":"jwt"}""", HttpStatusCode.OK, authTestJsonHeaders)
        }
        h.baseUrlHolder.baseUrl = LoginHarness.ORIGINAL_SERVER

        h.api.login(LoginRequestDto(username = "maya", password = "secret"))

        assertEquals(listOf<String?>(agent), seen.withLock { agents.toList() })
        h.close()
    }

    @Test
    fun `the project list check of a refused token carries the agent too`() = runTest {
        val seen = Mutex()
        val agents = mutableMapOf<String, String?>()
        val h = LoginHarness(userAgent = agent) { request ->
            seen.withLock { agents["${request.method.value} ${request.url.encodedPath}"] = request.headers[HttpHeaders.UserAgent] }
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/v2/tasks/12/comments" -> unauthorized()
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/v2/projects" -> respond(
                    """{"items":[],"total":0,"page":1,"per_page":1,"total_pages":1}""",
                    HttpStatusCode.OK,
                    authTestJsonHeaders,
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        h.signIn()

        assertFailsWith<VikunjaApiException> { h.api.createComment(12, "<p>hi</p>") }

        assertEquals(
            mapOf("POST /api/v2/tasks/12/comments" to agent, "GET /api/v2/projects" to agent),
            seen.withLock { agents.toMap() },
        )
        h.close()
    }
}
