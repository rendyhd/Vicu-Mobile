package com.rendyhd.vicu.auth

import com.rendyhd.vicu.data.remote.api.OidcCallbackDto
import com.rendyhd.vicu.data.remote.api.OidcProviderDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.remote.api.VikunjaProblemDto
import kotlin.concurrent.Volatile

sealed class OidcResult {
    data class Success(val token: String, val refreshToken: String? = null) : OidcResult()
    data class NeedsTOTP(val message: String? = null) : OidcResult()
    data class Error(val message: String) : OidcResult()

    /** The page asked for a passkey, which the sign-in web view cannot use: sign in in the browser. */
    data object PasskeyUnavailable : OidcResult()
}

class OidcHandler(
    private val apiServiceProvider: () -> VikunjaApiService,
) {
    companion object {
        private const val SCOPE = "openid email profile"
    }

    @Volatile
    private var pendingState: String? = null

    /** The redirect the pending code is issued for; Vikunja exchanges the code with it. */
    @Volatile
    private var pendingRedirectUri: String? = null

    data class AuthParams(
        val authUrl: String,
        val redirectUri: String,
        val state: String,
        val mode: OidcSignInMode = OidcSignInMode.WebView,
    )

    fun prepareAuthParams(
        provider: OidcProviderDto,
        vikunjaUrl: String,
        mode: OidcSignInMode = OidcSignInMode.WebView,
    ): AuthParams {
        val redirectUri = when (mode) {
            OidcSignInMode.WebView -> vikunjaCallback(vikunjaUrl, provider)
            OidcSignInMode.Browser -> OidcBrowserSignIn.REDIRECT_URI
        }
        val state = generateStateValue()
        pendingState = state
        pendingRedirectUri = redirectUri

        val authUrl = "${provider.authUrl}" +
            "?client_id=${encodeUrl(provider.clientId)}" +
            "&redirect_uri=${encodeUrl(redirectUri)}" +
            "&response_type=code" +
            "&state=$state" +
            "&scope=${encodeUrl(SCOPE)}"

        return AuthParams(authUrl, redirectUri, state, mode)
    }

    private fun vikunjaCallback(vikunjaUrl: String, provider: OidcProviderDto): String =
        "${vikunjaUrl.trimEnd('/')}/auth/openid/${provider.key}"

    suspend fun handleCallbackResult(
        code: String?,
        returnedState: String?,
        error: String?,
        provider: OidcProviderDto,
        vikunjaUrl: String,
        totpPasscode: String? = null,
    ): OidcResult {
        return try {
            if (error == OidcBrowserSignIn.PASSKEY_UNAVAILABLE) {
                pendingState = null
                return OidcResult.PasskeyUnavailable
            }
            if (error != null) {
                pendingState = null
                return OidcResult.Error("OIDC error: $error")
            }

            if (code.isNullOrBlank()) {
                pendingState = null
                return OidcResult.Error("No authorization code received")
            }

            val expectedState = pendingState
            pendingState = null

            if (expectedState == null || returnedState != expectedState) {
                return OidcResult.Error("OIDC state mismatch — possible CSRF attack")
            }

            val redirectUri = pendingRedirectUri ?: vikunjaCallback(vikunjaUrl, provider)

            val callbackDto = OidcCallbackDto(
                code = code,
                redirectUrl = redirectUri,
                scope = SCOPE,
                totpPasscode = totpPasscode.orEmpty(),
            )

            val response = apiServiceProvider().exchangeOidcToken(provider.key, callbackDto)
            if (!response.isSuccessful) {
                oidcTotpChallenge(response.problem)?.let { return it }
                return OidcResult.Error(
                    response.problem?.detail
                        ?: "OIDC token exchange failed: HTTP ${response.code()}",
                )
            }
            val tokenResponse = response.body()
            if (tokenResponse == null || tokenResponse.token.isBlank()) {
                OidcResult.Error("Empty token received from server")
            } else {
                val refreshToken = RefreshCookieExtractor.extractRefreshToken(response)
                OidcResult.Success(tokenResponse.token, refreshToken)
            }
        } catch (e: Exception) {
            pendingState = null
            OidcResult.Error("OIDC callback failed: ${e.message}")
        }
    }
}

internal fun oidcTotpChallenge(problem: VikunjaProblemDto?): OidcResult.NeedsTOTP? {
    if (!isTotpProblemCode(problem?.code)) return null
    return OidcResult.NeedsTOTP(problem?.detail?.takeIf(String::isNotBlank))
}

private const val HEX = "0123456789ABCDEF"
private const val UNRESERVED_MARKS = "_-!.~'()*"

/**
 * Percent-encodes [value] like Android's `Uri.encode`: letters, digits and `_-!.~'()*` stay as
 * they are. Common code, so both platforms build the same authorization URL and host tests can
 * run it (`Uri` is a stub there).
 */
fun encodeUrl(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (b < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in UNRESERVED_MARKS)) {
            append(c)
        } else {
            append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
    }
}

expect fun generateStateValue(): String
