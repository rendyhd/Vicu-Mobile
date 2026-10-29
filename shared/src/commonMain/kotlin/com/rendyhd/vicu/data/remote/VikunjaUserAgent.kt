package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.util.BuildInfo
import com.rendyhd.vicu.util.deviceName
import com.rendyhd.vicu.util.devicePlatformName

/**
 * The User-Agent of every request to the Vikunja server. Vikunja keeps the one of the sign-in
 * request as the session's device and lists it as it is under Settings, Sessions, where Ktor's
 * default read "ktor-client". It names the app, its version, the platform and this device:
 * `Vicu/2.5.1 (Android; Google Pixel 10 Pro)`. The desktop app sends the same form with the
 * computer's name (`src/main/user-agent.ts`).
 */
object VikunjaUserAgent {
    const val PRODUCT = "Vicu"
    private const val MAX_PART_LENGTH = 64

    fun current(): String = build(BuildInfo.versionName, devicePlatformName(), deviceName())

    fun build(version: String, platform: String, device: String): String {
        val cleanVersion = version.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-+_" }
        val product = if (cleanVersion.isEmpty()) PRODUCT else "$PRODUCT/$cleanVersion"
        val details = listOf(platform, device).map(::commentPart).filter { it.isNotEmpty() }
        return if (details.isEmpty()) product else "$product (${details.joinToString("; ")})"
    }

    /**
     * A header value is printable ASCII (OkHttp refuses anything else), and a comment cannot hold
     * its own delimiters, so "Rendy’s iPhone (2)" becomes "Rendys iPhone 2".
     */
    private fun commentPart(text: String): String =
        text.filter { it.code in 0x20..0x7E && it !in "();\\" }
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_PART_LENGTH)
            .trimEnd()
}
