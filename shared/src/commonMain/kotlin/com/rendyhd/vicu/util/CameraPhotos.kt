package com.rendyhd.vicu.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Photos taken from a task with the phone's camera (Take photo). */
object CameraPhotos {

    /**
     * The file name a photo gets, in the cameras' own form and local time:
     * `IMG_20261118_142233.jpg`. It becomes the attachment's name on the server.
     */
    fun fileName(at: LocalDateTime): String {
        fun two(n: Int) = n.toString().padStart(2, '0')
        val date = "${at.year.toString().padStart(4, '0')}${two(at.month.ordinal + 1)}${two(at.day)}"
        val time = "${two(at.hour)}${two(at.minute)}${two(at.second)}"
        return "IMG_${date}_$time.jpg"
    }

    fun fileName(now: Instant, zone: TimeZone): String = fileName(now.toLocalDateTime(zone))
}
