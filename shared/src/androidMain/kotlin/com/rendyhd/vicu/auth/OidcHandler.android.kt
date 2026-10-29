package com.rendyhd.vicu.auth

import java.util.UUID

actual fun generateStateValue(): String {
    return UUID.randomUUID().toString()
}
