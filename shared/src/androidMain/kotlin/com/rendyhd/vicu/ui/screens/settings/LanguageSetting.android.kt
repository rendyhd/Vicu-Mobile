package com.rendyhd.vicu.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android 13 and later: the system's "App language" screen for Vicu, which offers the languages of
 * `res/xml/locales_config.xml`. Older versions have no such screen.
 */
@Composable
actual fun rememberOpenLanguageSettings(): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val context = LocalContext.current
    return remember(context) {
        {
            val app = Uri.fromParts("package", context.packageName, null)
            try {
                context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // A system without that screen: the app's own page in Settings is the nearest place.
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
