package com.rendyhd.vicu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.rendyhd.vicu.data.local.ThemeMode

@Composable
fun VicuTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val deviceScheme = if (dynamicColor) platformDynamicColorScheme(darkTheme) else null
    val useDeviceColors = deviceScheme != null
    val baseScheme = deviceScheme ?: if (darkTheme) VicuDarkColorScheme else VicuLightColorScheme
    // Pure black only changes the dark theme; the Vicu scheme has it precomputed.
    val colorScheme = when {
        !darkTheme || !pureBlack -> baseScheme
        deviceScheme == null -> VicuBlackColorScheme
        else -> remember(deviceScheme) { deviceScheme.withPureBlack() }
    }

    // With device colours the status and swipe colours are shifted towards the wallpaper primary.
    val vicuColors = remember(colorScheme, darkTheme, useDeviceColors) {
        val base = if (darkTheme) VicuDarkColors else VicuLightColors
        if (useDeviceColors) base.harmonizedWith(colorScheme.primary) else base
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = VicuShapes,
    ) {
        CompositionLocalProvider(
            LocalVicuColors provides vicuColors,
            content = content,
        )
    }
}
