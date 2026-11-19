package com.rendyhd.vicu.worker

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.entity.TaskEntity
import com.rendyhd.vicu.data.remote.api.CreateTaskDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakeTaskDao
import com.rendyhd.vicu.data.repository.FakeTaskServer
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness
import com.rendyhd.vicu.data.repository.cachedTaskEntity
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.isRetriableNetworkError
import com.rendyhd.vicu.util.mayHaveReachedServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A network failure after the server began its answer (status line and headers in, the body cut
 * off: the connection reset, the phone switched networks) does not prove the request never reached
 * the server. A create that fails this way may already exist, so it is queued as "may have been
 * sent" and the replay looks for it before sending it again; otherwise the task would be made
 * twice. The same failure before any answer still queues the create as never sent, and a change to
 * an existing task cut off this way is queued like any other network failure.
 *
 * The desktop app had this mistake (it read Chromium's ERR_NETWORK_CHANGED as "never reached the
 * server") and fixed it with `isAnswerCutOff`. Here `mayHaveReachedServer` only says "never sent"
 * for failures that happen while connecting, so these tests pin that.
 */
class AnswerCutOffTest {

    /** A 201 whose body breaks after its first bytes, the way the OkHttp engine fails a body read. */
    private fun MockRequestHandleScope.createdThenCutOff(): HttpResponseData {
        val body: ByteReadChannel = CoroutineScope(Dispatchers.Unconfined).writer {
            channel.writeFully("""{"id":55,"ti""".encodeToByteArray())
            throw SocketException("Connection reset")
        }.channel
        return respond(body, HttpStatusCode.Created, authTestJsonHeaders)
    }

    // ---- The real OkHttp engine against a local socket --------------------------------------

    /**
     * Answers one request with the status line, the headers and part of the body, then resets the
     * connection, so OkHttp fails while reading the body.
     */
    private fun serverThatCutsOffItsAnswer(): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                repeat(contentLength) { input.read() }
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n" +
                        """{"id":55,"title":"Bu""").toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                socket.setSoLinger(true, 0) // a reset, not a clean end
            }
        }
        return server
    }

    private fun okHttpApi(port: Int) = VikunjaApiService(
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(authTestJson) }
            defaultRequest { url("http://127.0.0.1:$port/") }
        },
        authTestJson,
    )

    private fun createFailure(port: Int): Exception = runBlocking {
        try {
            okHttpApi(port).createTask(7, CreateTaskDto(title = "Buy milk"))
            fail("the create should have failed")
        } catch (e: Exception) {
            e
        }
    }

    @Test
    fun `OkHttp - an answer cut off after its headers may have reached the server`() {
        val server = serverThatCutsOffItsAnswer()
        val e = server.use { createFailure(it.localPort) }

        assertTrue(isRetriableNetworkError(e), "kept and sent again later: $e")
        assertTrue(mayHaveReachedServer(e), "the server answered, so the task may exist: $e")
    }

    @Test
    fun `OkHttp - a connection that never opened did not reach the server`() {
        // A port nobody listens on any more: the connection is refused before anything is sent.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val e = createFailure(port)

        assertTrue(isRetriableNetworkError(e), "kept and sent again later: $e")
        assertFalse(mayHaveReachedServer(e), "nothing went out: $e")
    }

    // ---- Creating a task online ---------------------------------------------------------------

    @Test
    fun `a create whose answer was cut off after the headers is queued as maybe sent`() = runTest {
        val h = TaskRepositoryHarness { createdThenCutOff() }

        val result = h.repository.create(Task(id = 0, title = "Buy milk", projectId = 7))

        val created = assertIs<NetworkResult.Success<Task>>(result).data
        assertTrue(created.id < 0, "kept under a temporary id")
        assertEquals("create", h.pendingActionDao.snapshot().single().actionType)
        assertTrue(h.tempIds.createMaybeSent(created.id), "the replay looks for it before creating it again")
    }

    @Test
    fun `the same network error before any answer queues the create as never sent`() = runTest {
        val h = TaskRepositoryHarness { throw ConnectException("Network is unreachable") }

        val result = h.repository.create(Task(id = 0, title = "Buy milk", projectId = 7))

        val created = assertIs<NetworkResult.Success<Task>>(result).data
        assertTrue(created.id < 0, "kept under a temporary id")
        assertEquals("create", h.pendingActionDao.snapshot().single().actionType)
        assertFalse(h.tempIds.createMaybeSent(created.id), "nothing reached the server, so nothing is looked for")
    }

    @Test
    fun `a change whose answer was cut off is still queued`() = runTest {
        val original = cachedTaskEntity(id = 42)
        val h = TaskRepositoryHarness(taskDao = FakeTaskDao(listOf(original))) { createdThenCutOff() }
        val domain = with(h.mapper) { original.toDomain() }

        val result = h.repository.update(domain.copy(title = "Edited"))

        assertIs<NetworkResult.Success<*>>(result)
        val queued = h.pendingActionDao.snapshot().single()
        assertEquals(42L, queued.entityId)
        assertEquals("update", queued.actionType)
        assertEquals("Edited", h.taskDao.entity(42)?.title)
    }

    // ---- The replay ---------------------------------------------------------------------------

    @Test
    fun `a replayed create whose answer was cut off is not created twice`() = runTest {
        val server = FakeTaskServer()
        var cutOffFirstCreate = true
        val h = SyncEngineHarness { request ->
            val answer = server.handle(this, request)
            if (cutOffFirstCreate && request.method == HttpMethod.Post && request.url.encodedPath == "/projects/7/tasks") {
                // The server made the task; its answer never arrived whole.
                cutOffFirstCreate = false
                createdThenCutOff()
            } else {
                answer
            }
        }
        val now = DateUtils.nowIso()
        h.pendingActionDao.insert(queuedCreate(-1, "Buy milk", createdAt = now))
        h.taskDao.upsert(TaskEntity(id = -1, title = "Buy milk", projectId = 7))

        h.engine.performSync()

        assertEquals(1, server.rows.values.count { it.title == "Buy milk" })
        assertTrue(h.tempIds.createMaybeSent(-1), "marked: the next replay looks for it")
        assertEquals("create", h.pendingActionDao.snapshot().single().actionType, "kept for the next replay")

        h.engine.performSync()

        assertEquals(1, server.rows.values.count { it.title == "Buy milk" }, "the task the first attempt made is taken")
        assertEquals(1, server.requestsLike("POST", "/projects/7/tasks").size, "sent once")
        assertTrue(h.pendingActionDao.snapshot().isEmpty())
        h.close()
    }
}
