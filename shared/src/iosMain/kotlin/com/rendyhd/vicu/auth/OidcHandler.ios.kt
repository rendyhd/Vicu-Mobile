package com.rendyhd.vicu.auth

import com.rendyhd.vicu.util.randomUuid

actual fun generateStateValue(): String = randomUuid()
