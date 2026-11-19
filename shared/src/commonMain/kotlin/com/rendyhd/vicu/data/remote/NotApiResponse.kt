package com.rendyhd.vicu.data.remote

import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

/**
 * An answer that did not come from Vikunja: the sign-in page of a network (a plane, a hotel), the
 * block page of a firewall or a reverse proxy, a sign-in proxy in front of the server. Vikunja never
 * saw the request, so the answer says nothing about the change or about the session: the change is
 * kept and sent again later, as when the server cannot be reached, and nobody is signed out.
 *
 * The rule is the desktop's (`isNotApiErrorAnswer` and `readSuccessBody`); keep them in step. It
 * needs positive evidence, a body that is there and is not JSON, because Vikunja answers every
 * error with a JSON object (`{"code","message"}` or a problem document).
 */
object NotApiResponse {
    /** On a call whose answer did not come from Vikunja ([KtorClientFactory] marks it). */
    internal val Marked = AttributeKey<Boolean>("VicuNotApiResponse")

    /** What to say when such an answer has to be shown; [status] is what the thing in between answered. */
    fun message(status: Int): String =
        "The answer did not come from your Vikunja server (HTTP $status). " +
            "A sign-in page of the network, a firewall or a proxy is probably in the way."

    /**
     * Statuses with which something in front of Vikunja judges the request itself (malformed, too
     * large): sending it again cannot help, so these stay refusals whoever answered.
     */
    private val ABOUT_THE_REQUEST = setOf(400, 413, 414, 431)

    /**
     * Whether an answer with [status] can be one that is not Vikunja's: a 4xx. A 408, a 429 and
     * every 5xx already mean "try again later" by themselves, and a redirect is followed or
     * refused by the HTTP client before anyone reads a body.
     */
    fun mayBeNotApiError(status: Int): Boolean =
        status in 400..499 && status != 408 && status != 429 && status !in ABOUT_THE_REQUEST

    /** True for an error answer whose [body] shows it is not Vikunja's. */
    fun isNotApiError(status: Int, body: String): Boolean = mayBeNotApiError(status) && isNotJson(body)

    /** True for the [body] of a 2xx answer that could not be read and is a page, not the API. */
    fun isNotApiSuccess(body: String): Boolean = isNotJson(body)

    /** A body that is there and is neither a JSON object nor a JSON array. An empty body shows nothing. */
    private fun isNotJson(body: String): Boolean {
        val text = body.trim().trimStart('﻿').trimStart()
        if (text.isEmpty()) return false
        if (text.first() != '{' && text.first() != '[') return true
        return runCatching { Json.parseToJsonElement(text) }.isFailure
    }
}
