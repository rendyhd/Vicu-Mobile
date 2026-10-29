package com.rendyhd.vicu.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.rendyhd.vicu.auth.OidcHandler
import com.rendyhd.vicu.auth.OidcSignInMode

@Composable
actual fun rememberOidcLauncher(
    onResult: (code: String?, state: String?, error: String?) -> Unit
): (OidcHandler.AuthParams) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intent = result.data
        if (intent == null) {
            onResult(null, null, "Cancelled")
            return@rememberLauncherForActivityResult
        }
        val code = intent.getStringExtra("code")
        val state = intent.getStringExtra("state")
        val error = intent.getStringExtra("error")
        onResult(code, state, error)
    }
    return { params ->
        // The in-app web view, or the browser for a provider that signs in with a passkey.
        val activity = when (params.mode) {
            OidcSignInMode.WebView -> "com.rendyhd.vicu.auth.OidcLoginActivity"
            OidcSignInMode.Browser -> "com.rendyhd.vicu.auth.OidcBrowserActivity"
        }
        val intent = Intent(context, Class.forName(activity)).apply {
            putExtra("auth_url", params.authUrl)
            putExtra("redirect_prefix", params.redirectUri)
            putExtra("expected_state", params.state)
        }
        launcher.launch(intent)
    }
}
