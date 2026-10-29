package com.rendyhd.vicu.util

/** The operating system: "Android", "iOS" or "iPadOS". */
expect fun devicePlatformName(): String

/** What this device is called: its make and model on Android, its name (or model) on iOS. */
expect fun deviceName(): String

fun getDeviceTokenTitle(): String = "Vicu — ${deviceName()}"
