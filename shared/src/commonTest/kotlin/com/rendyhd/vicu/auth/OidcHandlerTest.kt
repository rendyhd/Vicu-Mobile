package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Where the OIDC sign-in redirects to, and the passkey hand-over to the browser. */
class OidcHandlerTest {

    private val server = "https://tasks.example.com"
    private val provider = OidcProviderDto(
        key = "pocketid",
        name = "Pocket ID",
        authUrl = "https://id.example.com/authorize",
        clientId = "client-1",
    )

    @Test
    fun `the web view sign-in redirects to Vikunja's own callback`() {
        val params = OidcHandler { error("no request expected") }.prepareAuthParams(provider, "$server/")

        assertEquals(OidcSignInMode.WebView, params.mode)
        assertEquals("$server/auth/openid/pocketid", params.redirectUri)
        assertTrue(params.authUrl.contains("redirect_uri=${encodeUrl(params.redirectUri)}"))
    }

    @Test
    fun `the browser sign-in redirects to the app's own scheme`() {
        val params = OidcHandler { error("no request expected") }.prepareAuthParams(provider, server, OidcSignInMode.Browser)

        assertEquals(OidcSignInMode.Browser, params.mode)
        assertEquals("com.rendyhd.vicu:/oidc-callback", params.redirectUri)
        assertTrue(params.authUrl.startsWith("https://id.example.com/authorize?client_id=client-1"))
        assertTrue(params.authUrl.contains("redirect_uri=${encodeUrl(OidcBrowserSignIn.REDIRECT_URI)}"))
        assertTrue(params.authUrl.contains("state=${params.state}"))
    }

    @Test
    fun `a passkey prompt in the web view asks for the browser instead of failing`() = runTest {
        val handler = OidcHandler { error("no request expected") }
        handler.prepareAuthParams(provider, server)

        val result = handler.handleCallbackResult(null, null, OidcBrowserSignIn.PASSKEY_UNAVAILABLE, provider, server)

        assertEquals(OidcResult.PasskeyUnavailable, result)
    }

    @Test
    fun `Vikunja exchanges a browser code with the redirect it was issued for`() = runTest {
        val bodies = mutableListOf<String>()
        val h = LoginHarness { request ->
            if (request.url.encodedPath.endsWith("/auth/openid/pocketid/callback")) {
                bodies += (request.body as TextContent).text
                respond("""{"token":"jwt-1"}""", HttpStatusCode.OK, authTestJsonHeaders)
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        h.baseUrlHolder.baseUrl = server
        val handler = OidcHandler { h.api }
        val params = handler.prepareAuthParams(provider, server, OidcSignInMode.Browser)

        val result = handler.handleCallbackResult("code-1", params.state, null, provider, server)

        assertEquals("jwt-1", assertIs<OidcResult.Success>(result).token)
        assertTrue(bodies.single().contains("\"redirect_url\":\"com.rendyhd.vicu:/oidc-callback\""), bodies.single())
        h.close()
    }

    @Test
    fun `the passkey guard refuses modal requests, lets autofill through and signals the app`() {
        val script = OidcBrowserSignIn.passkeyGuardScript("tellTheApp()")

        assertTrue(script.contains("tellTheApp()"))
        assertTrue(script.contains("'NotAllowedError'"))
        // Older Pocket ID versions only recognise a message that says "timed out".
        assertTrue(script.contains("timed out"))
        assertTrue(script.contains("options.mediation !== 'conditional'"))
        // Android's WebView has no WebAuthn: the page must still reach the guard.
        assertTrue(script.contains("typeof window.PublicKeyCredential !== 'function'"))
        // On the prototype: WKWebView drops properties set on a container wrapper it later replaces.
        assertTrue(script.contains("Object.getPrototypeOf(credentials)"))
    }
}
