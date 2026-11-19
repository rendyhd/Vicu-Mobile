package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.auth.AuthState
import com.rendyhd.vicu.auth.FakeNetworkMonitor
import com.rendyhd.vicu.auth.InMemoryTokenStorage
import com.rendyhd.vicu.auth.LoginHarness
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_SERVER
import com.rendyhd.vicu.auth.LoginHarness.Companion.ORIGINAL_USER_ID
import com.rendyhd.vicu.auth.LoginHarness.Companion.unauthorized
import com.rendyhd.vicu.auth.LoginHarness.Companion.userJson
import com.rendyhd.vicu.auth.RecordingAuthHooks
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.remote.api.VikunjaApiException
import com.rendyhd.vicu.data.remote.api.isBrokenSearch
import com.rendyhd.vicu.util.isNetworkFailure
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.mayHaveReachedServer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An answer that did not come from Vikunja (the sign-in page of a plane's or hotel's network, a
 * firewall's block page) is no refusal: the request is sent again later and nobody is signed out.
 * The rule's cases are the desktop's (`error-classify.test.ts`, "isNotApiErrorAnswer").
 */
class NotApiResponseTest {

    private val page = "<!DOCTYPE html><html><body><h1>Sign in to the network</h1></body></html>"
    private val userPath = "/api/v2/user"
    private val refreshPath = "/api/v2/user/token/refresh"

    private fun MockRequestHandleScope.portal(status: HttpStatusCode): HttpResponseData =
        respond(page, status, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))

    // ---- the rule ---------------------------------------------------------------------------

    @Test
    fun `a 4xx whose body is a page is not Vikunja's answer`() {
        for (status in listOf(401, 403, 404, 405, 407, 409, 451)) {
            assertTrue(NotApiResponse.isNotApiError(status, page), "$status with a page")
            assertTrue(NotApiResponse.isNotApiError(status, "Forbidden"), "$status with plain text")
        }
    }

    @Test
    fun `Vikunja's own error answers stay what they are`() {
        val middleware = """{"code":11,"message":"missing, malformed, expired or otherwise invalid token provided"}"""
        val problem = """{"title":"Forbidden","status":403,"detail":"Forbidden"}"""
        for (status in listOf(401, 403, 404, 409, 412, 422)) {
            assertFalse(NotApiResponse.isNotApiError(status, middleware), "$status")
            assertFalse(NotApiResponse.isNotApiError(status, problem), "$status")
            assertFalse(NotApiResponse.isNotApiError(status, "  \n$problem\n"), "$status with whitespace")
            assertFalse(NotApiResponse.isNotApiError(status, "[]"), "$status with an array")
        }
    }

    @Test
    fun `an empty body shows nothing so the status stands`() {
        for (status in listOf(401, 403, 404)) {
            assertFalse(NotApiResponse.isNotApiError(status, ""), "$status")
            assertFalse(NotApiResponse.isNotApiError(status, "  \n"), "$status")
        }
    }

    @Test
    fun `a status about the request itself is a refusal whoever answered`() {
        for (status in listOf(400, 413, 414, 431)) {
            assertFalse(NotApiResponse.isNotApiError(status, page), "$status")
        }
    }

    @Test
    fun `statuses with a meaning of their own keep it`() {
        // Retried anyway (408, 429, 5xx), Vikunja's "nothing changed" (304), and redirects.
        for (status in listOf(200, 204, 301, 302, 304, 408, 429, 500, 502, 503, 504)) {
            assertFalse(NotApiResponse.isNotApiError(status, page), "$status")
        }
    }

    @Test
    fun `a body that starts like JSON but is not is a page`() {
        assertTrue(NotApiResponse.isNotApiError(403, "{ not json"))
        assertTrue(NotApiResponse.isNotApiSuccess(page))
        assertFalse(NotApiResponse.isNotApiSuccess(""))
        assertFalse(NotApiResponse.isNotApiSuccess("""{"id":1}"""))
    }

    @Test
    fun `such a failure is retried like an unreachable server and matches no status rule`() {
        val e = VikunjaApiException.notFromVikunja(403)

        assertTrue(e.notApiResponse)
        assertEquals(VikunjaApiException.NO_STATUS, e.httpStatus, "Vikunja gave no status")
        assertEquals(403, e.answeredStatus)
        assertTrue(isRetriableNetworkError(e))
        assertTrue(isNetworkFailure(e))
        assertFalse(mayHaveReachedServer(e), "Vikunja never saw the request")
        assertFalse(e.isBrokenSearch)
        assertTrue("HTTP 403" in e.message.orEmpty())

        val refused = VikunjaApiException(403)
        assertFalse(isRetriableNetworkError(refused), "Vikunja's own 403 is still a refusal")
        assertFalse(isNetworkFailure(refused))
    }

    // ---- through the HTTP client ------------------------------------------------------------

    private suspend fun LoginHarness.signInWithSession() {
        // Valid until 2100, so no refresh starts by itself during the test.
        storage.storeJwt("session-jwt", 4_102_444_800L)
        storage.storeRefreshToken("refresh-1")
        storage.storeAuthMethod("password")
        storage.storeVikunjaUrl(ORIGINAL_SERVER)
        storage.storeUserId(ORIGINAL_USER_ID)
        baseUrlHolder.baseUrl = ORIGINAL_SERVER
        authManager.ensureInitializedAndGetToken()
    }

    @Test
    fun `a firewall's 403 page is no refusal`() = runTest {
        val h = LoginHarness { portal(HttpStatusCode.Forbidden) }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertTrue(error.notApiResponse)
        assertEquals(403, error.answeredStatus)
        assertTrue(isRetriableNetworkError(error))
        h.close()
    }

    @Test
    fun `Vikunja's own 403 is still a refusal`() = runTest {
        val h = LoginHarness {
            respond("""{"title":"Forbidden","status":403,"detail":"Forbidden"}""", HttpStatusCode.Forbidden, authTestJsonHeaders)
        }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertFalse(error.notApiResponse)
        assertEquals(403, error.httpStatus)
        assertFalse(isRetriableNetworkError(error))
        h.close()
    }

    @Test
    fun `a 401 page starts no refresh and ends no session`() = runTest {
        val h = LoginHarness { portal(HttpStatusCode.Unauthorized) }
        h.signInWithSession()

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertTrue(error.notApiResponse)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        val requests = h.requests().map { it.toString() }
        assertEquals(1, requests.count { it.endsWith(userPath) }, "not sent a second time")
        assertTrue(requests.none { it.endsWith(refreshPath) }, "no refresh: $requests")
        h.close()
    }

    @Test
    fun `a 401 page ends no API token sign-in either`() = runTest {
        val h = LoginHarness { portal(HttpStatusCode.Unauthorized) }
        h.signIn()

        assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(AuthState.Authenticated, h.authManager.authState.value)
        assertEquals(1, h.requests().size, "the token is not checked against a page")
        h.close()
    }

    @Test
    fun `a refresh answered by a page keeps the session`() = runTest {
        // Vikunja refuses the JWT, and by the time the refresh goes out the network's page answers.
        val h = LoginHarness { request ->
            when (request.url.encodedPath) {
                refreshPath -> portal(HttpStatusCode.Forbidden)
                else -> unauthorized()
            }
        }
        h.signInWithSession()

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertEquals(401, error.httpStatus)
        assertEquals(AuthState.Authenticated, h.authManager.authState.value, "a page says nothing about the refresh token")
        h.close()
    }

    @Test
    fun `a page answered with 200 is not taken for the API`() = runTest {
        val h = LoginHarness { portal(HttpStatusCode.OK) }
        h.signIn()

        val error = assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        assertTrue(error.notApiResponse)
        assertTrue(isRetriableNetworkError(error))
        h.close()
    }

    @Test
    fun `the request after the page is read as usual`() = runTest {
        var blocked = true
        val h = LoginHarness { if (blocked) portal(HttpStatusCode.Forbidden) else userJson(ORIGINAL_USER_ID) }
        h.signIn()
        assertFailsWith<VikunjaApiException> { h.api.getCurrentUser() }

        blocked = false

        assertEquals(ORIGINAL_USER_ID, h.api.getCurrentUser().id)
        h.close()
    }

    @Test
    fun `the banner counts a page as no answer from the server`() = runTest {
        val reachability = ServerReachability()
        var answer: MockRequestHandleScope.() -> HttpResponseData = { portal(HttpStatusCode.Forbidden) }
        val storage = InMemoryTokenStorage()
        val authManager = AuthManager(
            platformAuthHooks = RecordingAuthHooks(),
            tokenStorage = storage,
            apiServiceProvider = { error("not used") },
            appScope = backgroundScope,
            networkMonitor = FakeNetworkMonitor(),
            deviceTokenTitle = { "Vicu — Test" },
        )
        val client = KtorClientFactory.create(
            MockEngine { answer() },
            Json { ignoreUnknownKeys = true },
            BaseUrlHolder(storage).apply { baseUrl = "https://vikunja.test" },
            authManager,
            reachability = reachability,
        )

        client.get("info")
        assertTrue(reachability.unreachable.value, "a firewall's page")

        answer = { respond("""{"title":"Forbidden","status":403}""", HttpStatusCode.Forbidden, authTestJsonHeaders) }
        client.get("info")
        assertFalse(reachability.unreachable.value, "Vikunja's own refusal is an answer")

        answer = { portal(HttpStatusCode.NotFound) }
        client.get("info")
        assertTrue(reachability.unreachable.value, "a proxy's 404 page while the server is away")

        answer = { respond("{}", HttpStatusCode.OK, authTestJsonHeaders) }
        client.get("info")
        assertFalse(reachability.unreachable.value)

        answer = { portal(HttpStatusCode.OK) }
        client.get("info")
        assertTrue(reachability.unreachable.value, "a sign-in page answered with 200")

        // An attachment may be an HTML file: a download says the server is there.
        client.get("tasks/4/attachments/9")
        assertFalse(reachability.unreachable.value)
    }
}
