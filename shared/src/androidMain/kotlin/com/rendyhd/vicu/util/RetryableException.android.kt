package com.rendyhd.vicu.util

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

actual fun isPlatformRetriableError(e: Exception): Boolean {
    // HTTP status errors (VikunjaApiException, Ktor's ResponseException) are decided in common code.
    // A TLS failure (an SSLException is an IOException) is retried like any other: a network that
    // puts its sign-in page in front of every HTTPS address breaks the handshake with a
    // certificate of its own, and the change must wait for the real server, not be refused.
    // Nothing is sent over a connection that failed to verify.
    if (e is IOException) return true
    if (e.cause is IOException) return true
    return false
}

private const val MAX_CAUSE_DEPTH = 8

actual fun isPlatformNetworkFailure(e: Exception): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is IOException) return true
        current = current.cause
        depth++
    }
    return false
}

actual fun isPlatformConnectionNeverOpened(e: Exception): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is UnknownHostException || current is ConnectException || current is NoRouteToHostException) {
            return true
        }
        // The TLS handshake failed (a certificate that does not verify or is for another host):
        // the request itself was never sent. Any other TLS failure may come after it.
        if (current is SSLHandshakeException || current is SSLPeerUnverifiedException) return true
        current = current.cause
        depth++
    }
    return false
}
