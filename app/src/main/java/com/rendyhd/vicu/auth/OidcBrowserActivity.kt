package com.rendyhd.vicu.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * OIDC sign-in in the system browser, for a provider that signs in with a passkey (the in-app
 * WebView has no WebAuthn). Opens the authorization URL in a Custom Tab where the browser offers
 * one (a plain browser otherwise), and waits for the redirect to [OidcBrowserSignIn.REDIRECT_URI],
 * which [OidcRedirectActivity] hands back here. Returns the same extras as [OidcLoginActivity].
 *
 * Coming back without a redirect (Back from the browser) counts as cancelled. `singleTask`, so the
 * redirect reaches this instance and clears the browser above it.
 */
class OidcBrowserActivity : ComponentActivity() {

    private var browserOpened = false
    private var leftForBrowser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserOpened = savedInstanceState?.getBoolean(KEY_OPENED) ?: false
        if (handleRedirect(intent)) return
        if (browserOpened) return

        val authUrl = intent.getStringExtra(OidcLoginActivity.EXTRA_AUTH_URL)
        if (authUrl == null) {
            finishWith(Activity.RESULT_CANCELED, Intent())
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            // The Custom Tabs protocol without the library: a session extra, even an empty one, asks
            // a browser that supports it for a Custom Tab.
            .putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
        try {
            startActivity(view)
            browserOpened = true
        } catch (_: ActivityNotFoundException) {
            finishWith(Activity.RESULT_CANCELED, Intent().putExtra(OidcLoginActivity.EXTRA_ERROR, "No browser is installed"))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    override fun onStop() {
        super.onStop()
        if (browserOpened) leftForBrowser = true
    }

    override fun onResume() {
        super.onResume()
        // Back here without the redirect: the user left the browser.
        if (leftForBrowser && !isFinishing) {
            finishWith(Activity.RESULT_CANCELED, Intent().putExtra(OidcLoginActivity.EXTRA_ERROR, "Cancelled"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OPENED, browserOpened)
    }

    /** True when [intent] carried the redirect, which then ends the activity. */
    private fun handleRedirect(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (!uri.toString().startsWith(OidcBrowserSignIn.REDIRECT_URI)) return false
        val error = uri.getQueryParameter("error")
        val result = if (error != null) {
            Intent().putExtra(OidcLoginActivity.EXTRA_ERROR, uri.getQueryParameter("error_description") ?: error)
        } else {
            Intent()
                .putExtra(OidcLoginActivity.EXTRA_CODE, uri.getQueryParameter("code"))
                .putExtra(OidcLoginActivity.EXTRA_STATE, uri.getQueryParameter("state"))
        }
        finishWith(if (error == null && uri.getQueryParameter("code") != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        return true
    }

    private fun finishWith(resultCode: Int, data: Intent) {
        setResult(resultCode, data)
        finish()
    }

    private companion object {
        const val KEY_OPENED = "browser_opened"
        const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    }
}

/**
 * Receives the browser's redirect to [OidcBrowserSignIn.REDIRECT_URI] and hands it to the waiting
 * [OidcBrowserActivity]; the only exported part of the browser sign-in. Anything can open this
 * address, so it carries no authority: the code is only used when its `state` is the one the
 * sign-in started with (OidcHandler).
 */
class OidcRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, OidcBrowserActivity::class.java)
                .setData(intent?.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
