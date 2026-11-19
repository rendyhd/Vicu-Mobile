package com.rendyhd.vicu.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Vicu's page in the Settings app, which has "Language" when the app lists more than one language
 * (`CFBundleLocalizations` in Info.plist) and the device has more than one preferred language.
 */
@Composable
actual fun rememberOpenLanguageSettings(): (() -> Unit)? = remember {
    {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
        Unit
    }
}
