package com.rendyhd.vicu.util

import android.os.Build

actual fun devicePlatformName(): String = "Android"

actual fun deviceName(): String {
    // Read as nullable: the JVM tests' android.jar stubs leave these fields null, and the HTTP
    // client asks for the name while Koin builds it (KoinGraphTest).
    val model: String? = Build.MODEL
    val deviceCode: String? = Build.DEVICE
    val manufacturer: String? = Build.MANUFACTURER
    val device = model?.takeIf { it.isNotBlank() } ?: deviceCode?.takeIf { it.isNotBlank() } ?: "Android"
    val manuf = manufacturer?.takeIf { it.isNotBlank() && !device.startsWith(it, ignoreCase = true) }
    return if (manuf != null) "$manuf $device" else device
}
