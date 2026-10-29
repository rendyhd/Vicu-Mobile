package com.rendyhd.vicu.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * WebView-based OIDC login screen.
 *
 * Uses Vikunja's own frontend callback URL as redirect_uri (already registered
 * in the IdP), then intercepts the redirect before the browser navigates to it
 * — exactly how the desktop Vicu app works with its BrowserWindow.
 *
 * The WebView has no WebAuthn, so a provider that signs in with a passkey (Pocket ID) cannot be
 * completed here: every page gets [OidcBrowserSignIn.passkeyGuardScript], and a passkey request
 * ends the activity with [OidcBrowserSignIn.PASSKEY_UNAVAILABLE] so setup offers the browser.
 */
class OidcLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTH_URL = "auth_url"
        const val EXTRA_REDIRECT_PREFIX = "redirect_prefix"
        const val EXTRA_EXPECTED_STATE = "expected_state"
        const val EXTRA_CODE = "code"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"

        private val PASSKEY_GUARD = OidcBrowserSignIn.passkeyGuardScript(
            "console.info('${OidcBrowserSignIn.PASSKEY_UNAVAILABLE}')",
        )
    }

    private var expectedState: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authUrl = intent.getStringExtra(EXTRA_AUTH_URL)
        val redirectPrefix = intent.getStringExtra(EXTRA_REDIRECT_PREFIX)
        expectedState = intent.getStringExtra(EXTRA_EXPECTED_STATE)

        if (authUrl == null || redirectPrefix == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (consoleMessage.message() == OidcBrowserSignIn.PASSKEY_UNAVAILABLE) {
                        setResult(
                            Activity.RESULT_CANCELED,
                            Intent().putExtra(EXTRA_ERROR, OidcBrowserSignIn.PASSKEY_UNAVAILABLE),
                        )
                        finish()
                        return true
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }
            webViewClient = object : WebViewClient() {
                // Twice, since a script run when a page starts may land before its document
                // exists; the guard installs itself once per page.
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    view.evaluateJavascript(PASSKEY_GUARD, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(PASSKEY_GUARD, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith(redirectPrefix)) {
                        handleRedirect(request.url)
                        return true
                    }
                    return false
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(authUrl)
    }

    private fun handleRedirect(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val desc = uri.getQueryParameter("error_description") ?: error
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, desc))
        } else {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            if (code != null) {
                setResult(
                    Activity.RESULT_OK,
                    Intent()
                        .putExtra(EXTRA_CODE, code)
                        .putExtra(EXTRA_STATE, state),
                )
            } else {
                setResult(Activity.RESULT_CANCELED)
            }
        }
        finish()
    }
}
