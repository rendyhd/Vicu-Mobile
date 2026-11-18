package com.rendyhd.vicu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rendyhd.vicu.ui.components.shared.FailedActionsBanner
import com.rendyhd.vicu.ui.components.shared.OfflineBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bands at the top report their height to VicuApp, which takes that much of the status bar's
 * inset off the screens below. Once a band has closed the height must come back to 0. With
 * animations off (power saving, "Remove animations") a band closes in one frame, and a height
 * read from the band itself stayed at the whole band, so every screen lost the status bar's inset
 * until the activity was destroyed (issue 110).
 */
@RunWith(AndroidJUnit4::class)
class TopBandInsetsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        },
    )

    @Test
    fun offlineBandHeightReturnsToZeroWhenItClosesWithoutAnimation() {
        var offline by mutableStateOf(true)
        var height = -1
        composeRule.setContent {
            MaterialTheme {
                OfflineBanner(
                    isOffline = offline,
                    serverUnreachable = false,
                    pendingCount = 0,
                    modifier = Modifier.onSizeChanged { height = it.height },
                )
            }
        }
        composeRule.runOnIdle { assertTrue("band is shown", height > 0) }

        composeRule.runOnIdle { offline = false }

        composeRule.runOnIdle { assertEquals(0, height) }
    }

    @Test
    fun failedBandHeightReturnsToZeroWhenItClosesWithoutAnimation() {
        var failed by mutableStateOf(2)
        var height = -1
        composeRule.setContent {
            MaterialTheme {
                FailedActionsBanner(
                    failedCount = failed,
                    onRetry = {},
                    onDiscard = {},
                    modifier = Modifier.onSizeChanged { height = it.height },
                )
            }
        }
        composeRule.runOnIdle { assertTrue("band is shown", height > 0) }

        composeRule.runOnIdle { failed = 0 }

        composeRule.runOnIdle { assertEquals(0, height) }
    }
}
