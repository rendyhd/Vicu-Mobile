package com.rendyhd.vicu.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the last request said about the server. The device can have a network while the server
 * is down, which the network monitor cannot see; the banner says so from this. [KtorClientFactory]
 * reports every request of the Vikunja client here.
 */
class ServerReachability {
    private val _unreachable = MutableStateFlow(false)

    /**
     * True since a request got no answer, a proxy's answer that the server behind it is gone, or
     * an answer something in front of the server gave in its place ([NotApiResponse]).
     */
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    fun answered(status: Int) {
        _unreachable.value = status in GATEWAY_DOWN
    }

    fun noAnswer() {
        _unreachable.value = true
    }

    private companion object {
        /** Bad gateway, service unavailable, gateway timeout: a reverse proxy is up, the server is not. */
        val GATEWAY_DOWN = 502..504
    }
}
