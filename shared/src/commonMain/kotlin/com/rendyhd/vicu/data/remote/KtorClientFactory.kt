package com.rendyhd.vicu.data.remote

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.isNetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

object KtorClientFactory {
    /** Endpoints (relative to the API root) that are called without a token. */
    private val AUTH_EXEMPT_ENDPOINTS = setOf("login", "info", "user/token/refresh")

    /** OIDC sign-in: `auth/openid/{provider}/callback`. */
    private const val OPENID_ENDPOINT = "auth/openid"
    private const val TOKEN_REFRESH_ENDPOINT = "user/token/refresh"

    /**
     * The collaboration endpoints (cross-app semantics 9): comments, assignees, the read mark,
     * following, project members and shares, and finding a person. An API token can lack the
     * permission for them while the session still works ([ApiTokenPermission]).
     */
    private val COLLABORATION_ENDPOINTS = listOf(
        Regex("""tasks/\d+/(comments|assignees)(/\d+)?"""),
        Regex("""tasks/\d+/read"""),
        Regex("""subscriptions/task/\d+"""),
        Regex("""projects/\d+/(users|teams)(/[^/]+)?"""),
        Regex("""users"""),
    )

    /**
     * [encodedPath] relative to the API root, without slashes at either end. [apiBasePath] is the
     * path of the API root (`/api/v2/`, or `/prefix/api/v2/` for a server in a sub-path); when it
     * is not known yet the path is taken to be the endpoint itself.
     */
    private fun endpointOf(encodedPath: String, apiBasePath: String): String {
        val base = if (apiBasePath.isEmpty()) "" else apiBasePath.trimEnd('/') + "/"
        return encodedPath.removePrefix(base).trim('/')
    }

    /**
     * True for the endpoints called before there is a token. A path only counts when the whole
     * endpoint matches: `/projects/3/info` or `/information` are ordinary requests that need the
     * token, which a substring test would have left without it.
     */
    internal fun isAuthExemptPath(encodedPath: String, apiBasePath: String): Boolean {
        val endpoint = endpointOf(encodedPath, apiBasePath)
        return endpoint in AUTH_EXEMPT_ENDPOINTS ||
            endpoint == OPENID_ENDPOINT ||
            endpoint.startsWith("$OPENID_ENDPOINT/")
    }

    internal fun isTokenRefreshPath(encodedPath: String, apiBasePath: String): Boolean =
        endpointOf(encodedPath, apiBasePath) == TOKEN_REFRESH_ENDPOINT

    private val ATTACHMENT_ENDPOINT = Regex("""tasks/\d+/attachments/\d+""")

    /** True for one attachment of a task: the only endpoint that answers with a file. */
    internal fun isAttachmentPath(encodedPath: String, apiBasePath: String): Boolean =
        ATTACHMENT_ENDPOINT.matches(endpointOf(encodedPath, apiBasePath))

    /** True for the collaboration endpoints, by whole endpoint (`user`, the signed-in user, is not one). */
    internal fun isCollaborationPath(encodedPath: String, apiBasePath: String): Boolean {
        val endpoint = endpointOf(encodedPath, apiBasePath)
        return COLLABORATION_ENDPOINTS.any { it.matches(endpoint) }
    }

    /**
     * The status the project list (one project) answers [token] with, or null when no answer
     * came. Sent past this client's own 401 handling, so it never refreshes or ends the session.
     */
    private suspend fun Sender.projectListStatus(
        refused: HttpRequestBuilder,
        token: String,
        apiBasePath: String,
        userAgent: String,
    ): Int? = try {
        val check = HttpRequestBuilder().apply {
            method = HttpMethod.Get
            url.takeFrom(refused.url)
            url.encodedPath = apiBasePath.trimEnd('/') + "/projects"
            url.parameters.clear()
            url.parameters.append("per_page", "1")
            url.parameters.append("page", "1")
            url.fragment = ""
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, userAgent)
        }
        // An answer that is not Vikunja's is no answer about the token.
        execute(check).takeUnless { it.isNotFromVikunja() }?.response?.status?.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun HttpClientCall.isNotFromVikunja(): Boolean = attributes.getOrNull(NotApiResponse.Marked) == true

    private fun apiBasePathOf(baseUrlHolder: BaseUrlHolder): String {
        val fullBaseUrl = baseUrlHolder.getFullBaseUrl()
        return if (fullBaseUrl.isEmpty()) "" else Url(fullBaseUrl).encodedPath
    }

    fun create(
        engine: HttpClientEngine,
        json: Json,
        baseUrlHolder: BaseUrlHolder,
        authManager: AuthManager,
        // Request logging puts every URL (server host, search terms, filters) into logcat, so it
        // is opt-in and only the debug build turns it on (see KoinModules).
        enableLogging: Boolean = false,
        tokenCheck: ApiTokenCheck = ApiTokenCheck(),
        // The server lists a session under the User-Agent of its sign-in ([VikunjaUserAgent]).
        userAgent: String = VikunjaUserAgent.PRODUCT,
        reachability: ServerReachability = ServerReachability(),
    ): HttpClient {
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }

            install(UserAgent) {
                agent = userAgent
            }

            // No timeouts of its own: every request keeps the engine's, except those that set one
            // (an upload gets time by its size, `uploadTimeoutMillis`).
            install(HttpTimeout)

            if (enableLogging) {
                install(Logging) {
                    logger = object : io.ktor.client.plugins.logging.Logger {
                        override fun log(message: String) {
                            com.rendyhd.vicu.util.Logger.d("KtorClient", message)
                        }
                    }
                    // Method, URL, and status only. Never log auth/cookie headers or bodies.
                    level = LogLevel.INFO
                }
            }
        }

        // 1. Dynamic Base URL Redirection Interceptor
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            var fullBaseUrl = baseUrlHolder.getFullBaseUrl()
            if (fullBaseUrl.isEmpty()) {
                baseUrlHolder.ensureInitialized()
                fullBaseUrl = baseUrlHolder.getFullBaseUrl()
            }

            if (fullBaseUrl.isNotEmpty()) {
                val originalPath = context.url.encodedPath.removePrefix("/")
                context.url.takeFrom(fullBaseUrl)
                if (originalPath.isNotEmpty()) {
                    val basePath = context.url.encodedPath
                    val separator = if (basePath.endsWith("/")) "" else "/"
                    context.url.encodedPath = "$basePath$separator$originalPath"
                }
            }
        }

        // 2. Authorization Header Injection Interceptor
        client.requestPipeline.intercept(HttpRequestPipeline.State) {
            val path = context.url.encodedPath
            if (isAuthExemptPath(path, apiBasePathOf(baseUrlHolder))) {
                return@intercept
            }

            var token = authManager.getBestTokenSync()
            if (token.isNullOrBlank()) {
                token = authManager.ensureInitializedAndGetToken()
            }

            if (!token.isNullOrBlank()) {
                context.header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

        // 3. 401 Reactive Token Authenticator
        client.plugin(HttpSend).intercept { request ->
            val path = request.url.encodedPath
            var response = execute(request)

            // A 401 that something in front of Vikunja gave (a network's sign-in page, a proxy)
            // says nothing about the token: no refresh, and the session stays ([NotApiResponse]).
            if (response.response.status == HttpStatusCode.Unauthorized &&
                !response.isNotFromVikunja() &&
                !isTokenRefreshPath(path, apiBasePathOf(baseUrlHolder))
            ) {
                val failedToken = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                val apiBasePath = apiBasePathOf(baseUrlHolder)
                // An API token without the permission for a route group (comments, assignees,
                // sharing, attachments, relations ...) is answered 401 like an ended session. When
                // the same token still reads the project list, only this request was refused: the
                // session stays, and the call is marked so that VikunjaApiService says what the
                // token lacks (cross-app semantics 9.7). Asked here, outside the refresh lock, and
                // at most once a minute ([ApiTokenCheck]).
                if (failedToken != null &&
                    authManager.isApiToken(failedToken) &&
                    tokenCheck.readsProjects(failedToken) { projectListStatus(request, failedToken, apiBasePath, userAgent) }
                ) {
                    val message = if (isCollaborationPath(path, apiBasePath)) {
                        ApiTokenPermission.TOKEN_LACKS_PERMISSION
                    } else {
                        ApiTokenPermission.TOKEN_LACKS_ROUTE_PERMISSION
                    }
                    response.attributes.put(ApiTokenPermission.Refused, message)
                    return@intercept response
                }

                // One retry at most: the answer to the retry is returned as it is.
                val retryToken = authManager.tokenAfterUnauthorized(failedToken)
                if (retryToken != null) {
                    request.headers[HttpHeaders.Authorization] = "Bearer $retryToken"
                    response = execute(request)
                }
            }

            response
        }

        // 4. Whether the server answers, for the banner. Registered last, so it is the innermost
        // sender and sees each attempt, the retry after a token refresh included. An error answer
        // that is not Vikunja's (a network's sign-in page, a firewall: [NotApiResponse]) is marked
        // here for the 401 handling above and counts as no answer.
        client.plugin(HttpSend).intercept { request ->
            val call = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNetworkFailure(e)) reachability.noAnswer()
                throw e
            }
            val status = call.response.status.value
            // The request's attributes are also those of a second attempt: an earlier mark goes.
            call.attributes.remove(NotApiResponse.Marked)
            if (!NotApiResponse.mayBeNotApiError(status)) {
                // A page answered with 2xx (a sign-in proxy, a network's sign-in page) is not the
                // server either: the API never answers HTML. Only a downloaded attachment can be
                // an HTML file. Its body is left for the caller, who reads it as a failure.
                val page = status in 200..299 &&
                    call.response.contentType()?.match(ContentType.Text.Html) == true &&
                    !isAttachmentPath(request.url.encodedPath, apiBasePathOf(baseUrlHolder))
                if (page) reachability.noAnswer() else reachability.answered(status)
                return@intercept call
            }
            // An error answer is small: it is kept in memory, so it can be read here and again
            // by the caller.
            val saved = call.save()
            val body = try {
                saved.response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            if (NotApiResponse.isNotApiError(status, body)) {
                saved.attributes.put(NotApiResponse.Marked, true)
                reachability.noAnswer()
            } else {
                reachability.answered(status)
            }
            saved
        }

        return client
    }
}
