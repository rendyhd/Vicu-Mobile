package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CameraPhotosTest {

    @Test
    fun `a photo is named like the cameras name theirs`() {
        assertEquals("IMG_20261118_142233.jpg", CameraPhotos.fileName(LocalDateTime(2026, 11, 18, 14, 22, 33)))
    }

    @Test
    fun `single digits are padded`() {
        assertEquals("IMG_20260102_030405.jpg", CameraPhotos.fileName(LocalDateTime(2026, 1, 2, 3, 4, 5)))
    }

    @Test
    fun `the name uses local time`() {
        val instant = Instant.parse("2026-11-18T23:30:00Z")
        assertEquals("IMG_20261119_003000.jpg", CameraPhotos.fileName(instant, TimeZone.of("Europe/Amsterdam")))
    }
}
