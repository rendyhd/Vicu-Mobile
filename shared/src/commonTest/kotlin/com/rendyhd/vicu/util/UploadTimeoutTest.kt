package com.rendyhd.vicu.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** The same numbers as the desktop's `uploadTimeoutMs` (src/main/upload-limits.ts). */
class UploadTimeoutTest {

    @Test
    fun `a small file gets a minute`() {
        assertEquals(60_000L, uploadTimeoutMillis(0))
        assertEquals(61_000L, uploadTimeoutMillis(1))
        assertEquals(61_000L, uploadTimeoutMillis(65_536))
    }

    @Test
    fun `a larger file gets a second per 64 KB on top`() {
        assertEquals(97_000L, uploadTimeoutMillis(2_400_000))
        assertEquals(366_000L, uploadTimeoutMillis(20_000_000))
    }

    @Test
    fun `the time is capped at 30 minutes which is also what an unknown size gets`() {
        assertEquals(1_800_000L, uploadTimeoutMillis(10_000_000_000))
        assertEquals(1_800_000L, uploadTimeoutMillis(null))
    }
}
