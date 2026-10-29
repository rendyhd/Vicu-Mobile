package com.rendyhd.vicu.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.ThemeMode
import com.rendyhd.vicu.data.local.ThemePrefsStore
import com.rendyhd.vicu.ui.theme.VicuTheme
import org.koin.mp.KoinPlatform
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

/**
 * The Compose UI of the app, shown by the Swift app's window. [restoredUiState] is what the scene
 * kept from [com.rendyhd.vicu.ios.IosApp.saveUiState] the last time (empty for a fresh start).
 *
 * The screens make room for the keyboard themselves (`imePadding`), so the view is not also panned
 * up to show the focused field: that pan pushed the editor's top bar (Back, Done) off the screen.
 */
fun MainViewController(restoredUiState: String): UIViewController = ComposeUIViewController(
    configure = { onFocusBehavior = OnFocusBehavior.DoNothing },
) {
    val koin = remember { KoinPlatform.getKoin() }
    val authManager = remember { koin.get<AuthManager>() }
    val themePrefs = remember { koin.get<ThemePrefsStore>() }
    val inputs = remember { koin.get<AppInputs>() }
    val themeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.System)
    val pureBlack by themePrefs.pureBlack.collectAsState(initial = false)

    // The window follows the app's theme, so the status bar and system sheets match it.
    val controller = LocalUIViewController.current
    LaunchedEffect(themeMode, controller) {
        controller.view.window?.overrideUserInterfaceStyle = when (themeMode) {
            ThemeMode.Dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
            ThemeMode.Light -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.System -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        }
    }

    VicuTheme(themeMode = themeMode, dynamicColor = false, pureBlack = pureBlack) {
        RestorableUiState(restoredUiState) {
            VicuApp(
                authManager = authManager,
                initialTaskId = inputs.initialTaskId,
                onInitialTaskConsumed = { inputs.initialTaskId.value = null },
                showTaskEntry = inputs.showTaskEntry,
                showTaskEntryProjectId = inputs.showTaskEntryProjectId,
                onShowTaskEntryConsumed = {
                    inputs.showTaskEntry.value = false
                    inputs.showTaskEntryProjectId.value = null
                },
                navigateToView = inputs.navigateToView,
                onNavigateToViewConsumed = { inputs.navigateToView.value = null },
                sharedContent = inputs.sharedContent,
                onSharedContentConsumed = { inputs.sharedContent.value = null },
            )
        }
    }
}
