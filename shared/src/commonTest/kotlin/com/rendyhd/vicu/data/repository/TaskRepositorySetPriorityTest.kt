package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.jsonOk
import com.rendyhd.vicu.data.repository.TaskRepositoryHarness.Companion.serviceUnavailable
import com.rendyhd.vicu.util.NetworkResult
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A swipe set to "Urgent": only the priority of the stored task changes. */
class TaskRepositorySetPriorityTest {

    private val patchResponse =
        """{"id":42,"title":"Newer title","description":"Newer description","project_id":7,"priority":4}"""

    private fun harness(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { jsonOk(patchResponse) },
    ) = TaskRepositoryHarness(
        taskDao = FakeTaskDao(
            listOf(cachedTaskEntity(id = 42, title = "Newer title", description = "Newer description")),
        ),
        handler = handler,
    )

    @Test
    fun `the patch contains only the priority and is built from the current Room row`() = runTest {
        val h = harness()

        val result = h.repository.setPriority(42, 4)

        assertIs<NetworkResult.Success<*>>(result)
        val patch = h.patches().single()
        assertEquals("/tasks/42", patch.path)
        assertEquals(setOf("priority"), patch.bodyJson!!.keys)
        assertEquals(JsonPrimitive(4), patch.bodyJson!!["priority"])
    }

    @Test
    fun `an unknown task id sends nothing`() = runTest {
        val h = harness()

        val result = h.repository.setPriority(999, 4)

        assertIs<NetworkResult.Error>(result)
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `offline the queued patch contains only the priority`() = runTest {
        val h = harness(handler = { serviceUnavailable() })

        h.repository.setPriority(42, 4)

        val queued = h.pendingActionDao.snapshot().single()
        assertEquals("update", queued.actionType)
        val payload = Json.parseToJsonElement(queued.payload)
        assertEquals(setOf("priority"), (payload as JsonObject).keys)
        assertNotNull(h.taskDao.entity(42))
    }
}
