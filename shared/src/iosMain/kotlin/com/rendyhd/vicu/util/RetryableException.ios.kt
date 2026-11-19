package com.rendyhd.vicu.util

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.io.IOException
import platform.Foundation.NSURLErrorDomain

private const val MAX_CAUSE_DEPTH = 8

// NSURLError codes (Foundation's NSURLError.h).
private const val CANCELLED = -999L
private const val BAD_URL = -1000L
private const val UNSUPPORTED_URL = -1002L
private const val CANNOT_FIND_HOST = -1003L
private const val CANNOT_CONNECT_TO_HOST = -1004L
private const val DNS_LOOKUP_FAILED = -1006L
private const val NOT_CONNECTED_TO_INTERNET = -1009L
private const val INSECURE_CONNECTION_BLOCKED = -1022L // App Transport Security
private val TLS_FAILURES = -1206L..-1200L

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

/** The NSURLErrorDomain code of a failed request, or null for any other failure. */
internal fun Throwable.urlErrorCode(): Long? =
    causes().firstNotNullOfOrNull { cause ->
        (cause as? DarwinHttpRequestException)?.origin?.takeIf { it.domain == NSURLErrorDomain }?.code
    }

/**
 * Like Android's rule (an IOException is worth retrying), read from the NSURLError code when the
 * Darwin engine gives one. A TLS failure is retried too: a network that puts its sign-in page in
 * front of every HTTPS address breaks the handshake with a certificate of its own, and the change
 * must wait for the real server. Nothing is sent over a connection that failed to verify.
 */
actual fun isPlatformRetriableError(e: Exception): Boolean {
    val code = e.urlErrorCode()
    if (code != null) {
        return code !in setOf(CANCELLED, BAD_URL, UNSUPPORTED_URL, INSECURE_CONNECTION_BLOCKED)
    }
    return e is IOException || e.cause is IOException
}

actual fun isPlatformNetworkFailure(e: Exception): Boolean =
    e.urlErrorCode() != null || e.causes().any { it is IOException }

/** The request never reached a server: no host, no route, no connection, or a TLS handshake that failed. */
actual fun isPlatformConnectionNeverOpened(e: Exception): Boolean {
    val code = e.urlErrorCode() ?: return false
    return code in TLS_FAILURES ||
        code in setOf(CANNOT_FIND_HOST, CANNOT_CONNECT_TO_HOST, DNS_LOOKUP_FAILED, NOT_CONNECTED_TO_INTERNET)
}
