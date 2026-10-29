package com.rendyhd.vicu.util

import platform.UIKit.UIDevice

actual fun devicePlatformName(): String = UIDevice.currentDevice.systemName

actual fun deviceName(): String {
    val model = UIDevice.currentDevice.model
    val name = UIDevice.currentDevice.name
    return if (name.isNotBlank() && name != model) name else model
}
