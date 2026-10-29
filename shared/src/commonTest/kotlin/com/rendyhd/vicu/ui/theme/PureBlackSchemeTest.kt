package com.rendyhd.vicu.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PureBlackSchemeTest {

    private val pageRoles = setOf("background", "surface", "surfaceDim", "surfaceContainerLowest")

    @Test
    fun `the page and the surfaces at its level are black`() {
        val roles = VicuBlackColorScheme.roles()
        for (role in pageRoles) assertEquals(Color.Black, roles.getValue(role), role)
    }

    @Test
    fun `every other role is the same as in the dark scheme`() {
        val dark = VicuDarkColorScheme.roles()
        val black = VicuBlackColorScheme.roles()
        assertEquals(dark.keys, black.keys)
        for (role in dark.keys - pageRoles) assertEquals(dark.getValue(role), black.getValue(role), role)
    }

    @Test
    fun `raised containers stay lighter than the page and in order`() {
        val s = VicuBlackColorScheme
        val levels = listOf(
            s.surface,
            s.surfaceContainerLow,
            s.surfaceContainer,
            s.surfaceContainerHigh,
            s.surfaceContainerHighest,
        ).map { it.luminance() }
        assertTrue(levels.zipWithNext().all { (lower, higher) -> lower < higher }, "levels: $levels")
    }

    @Test
    fun `a device scheme keeps its own colours apart from the page`() {
        val device = darkColorScheme(primary = Color(0xFF8BD0A0), surface = Color(0xFF101410))
        val black = device.withPureBlack()
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.background)
        assertEquals(device.primary, black.primary)
        assertEquals(device.surfaceContainer, black.surfaceContainer)
    }
}
