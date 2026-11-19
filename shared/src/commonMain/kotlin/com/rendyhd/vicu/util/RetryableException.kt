package com.rendyhd.vicu.util

import io.ktor.client.plugins.ResponseException
import com.rendyhd.vicu.data.remote.api.VikunjaApiException

/**
 * True when a failed request is worth sending again later: no answer at all (offline, a
 * timeout, a TLS failure such as a network's own certificate), a server error (5xx), too many
 * requests (429) or a request timeout (408), as on the desktop, or an answer that did not come
 * from Vikunja at all (a network's sign-in page, a firewall: `NotApiResponse`). Any other answer
 * refuses the request itself.
 */
fun isRetriableNetworkError(e: Exception): Boolean {
    if (e is VikunjaApiException) return e.notApiResponse || isRetriableStatus(e.httpStatus)
    if (e is ResponseException) return isRetriableStatus(e.response.status.value)
    return isPlatformRetriableError(e)
}

private fun isRetriableStatus(code: Int): Boolean = code in 500..599 || code == 429 || code == 408

expect fun isPlatformRetriableError(e: Exception): Boolean

/**
 * True when [e] means no answer of the server was received at all: offline, DNS failure, timeout,
 * connection reset, TLS failure, or an answer that something in front of the server gave in its
 * place (`NotApiResponse`). Unlike [isRetriableNetworkError] this says nothing about whether a
 * retry is worthwhile.
 */
fun isNetworkFailure(e: Exception): Boolean =
    (e is VikunjaApiException && e.notApiResponse) || isPlatformNetworkFailure(e)

expect fun isPlatformNetworkFailure(e: Exception): Boolean

/**
 * True when a request that failed may still have been carried out by the server: a 5xx, a
 * timeout, a connection that broke after the request went out. False when it certainly was not:
 * an answer that refuses it (4xx, 429) or a connection that never opened (no network, unknown
 * host, connection refused, a TLS handshake that failed). A create that may have been carried
 * out is looked for on the server before it is sent again.
 */
fun mayHaveReachedServer(e: Exception): Boolean {
    if (e is VikunjaApiException) return e.httpStatus in 500..599
    if (e is ResponseException) return e.response.status.value in 500..599
    return !isPlatformConnectionNeverOpened(e)
}

/** True when [e] says the connection to the server was never made, so no request went out. */
expect fun isPlatformConnectionNeverOpened(e: Exception): Boolean
