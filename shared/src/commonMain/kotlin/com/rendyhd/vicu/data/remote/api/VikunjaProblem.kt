package com.rendyhd.vicu.data.remote.api

import com.rendyhd.vicu.data.remote.NotApiResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VikunjaProblemDto(
    val title: String = "",
    val status: Int = 0,
    val detail: String = "",
    val code: Long = 0,
    val errors: List<VikunjaErrorDetailDto>? = null,
)

@Serializable
data class VikunjaErrorDetailDto(
    val location: String = "",
    val message: String = "",
    val value: JsonElement? = null,
)

class VikunjaApiException(
    val httpStatus: Int,
    val problem: VikunjaProblemDto? = null,
    /**
     * The API token lacks the permission for this request while the session still works
     * (cross-app semantics 9.7). [httpStatus] is then 403, and the message says what to do.
     */
    val tokenPermission: Boolean = false,
    message: String? = null,
    /**
     * The answer did not come from Vikunja ([NotApiResponse]): something in between answered, so
     * Vikunja never saw the request. [httpStatus] is then [NO_STATUS], because Vikunja gave none
     * and no rule about a 401, 403 or 404 of the server may apply; [answeredStatus] has the
     * status that came. Such a failure is retried like an unreachable server.
     */
    val notApiResponse: Boolean = false,
    /** The status that was answered; differs from [httpStatus] only for [notApiResponse]. */
    val answeredStatus: Int = httpStatus,
) : Exception(
    message
        ?: problem?.detail?.takeIf { it.isNotBlank() }
        ?: problem?.title?.takeIf { it.isNotBlank() }
        ?: "Vikunja request failed (HTTP $httpStatus)",
) {
    companion object {
        /** [httpStatus] of an answer that was not Vikunja's. */
        const val NO_STATUS = 0

        /** The failure for an answer with [status] that did not come from Vikunja. */
        fun notFromVikunja(status: Int): VikunjaApiException = VikunjaApiException(
            httpStatus = NO_STATUS,
            message = NotApiResponse.message(status),
            notApiResponse = true,
            answeredStatus = status,
        )
    }
}

/**
 * True when this is the server's answer to a `q` search while the session still works: the
 * server's search is broken, not the connection or the sign-in. A ParadeDB `pg_search` extension
 * older than the library Postgres loads answers every search with a 500 ("function
 * paradedb.ctid_is_valid(tid) does not exist") while listings without `q` keep working, so the
 * sync lists without `q` instead of failing. An answer that was not Vikunja's says nothing about
 * its search.
 */
val VikunjaApiException.isBrokenSearch: Boolean get() = httpStatus != 401 && !notApiResponse
