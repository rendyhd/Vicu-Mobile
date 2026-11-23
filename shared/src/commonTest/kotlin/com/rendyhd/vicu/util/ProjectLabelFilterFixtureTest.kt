package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the `projectLabelFilter` vectors of `test-fixtures/cross-app-semantics-v1.json` (contract section 17.2). */
class ProjectLabelFilterFixtureTest {
    @Serializable
    private data class FixtureLabel(val id: Long, val title: String)

    @Serializable
    private data class FixtureTask(val id: Long, val title: String, val labels: List<Long>, val parent: Long? = null)

    @Serializable
    private data class Count(val id: Long, val count: Int)

    @Serializable
    private data class ChoicesVector(val name: String, val picked: List<Long>, val expect: List<Count>)

    @Serializable
    private data class ShownVector(val name: String, val picked: List<Long>, val expect: List<Long>)

    @Serializable
    private data class Vectors(
        val labels: List<FixtureLabel>,
        val tasks: List<FixtureTask>,
        val choices: List<ChoicesVector>,
        val shown: List<ShownVector>,
    )

    @Serializable
    private data class Root(val projectLabelFilter: Vectors)

    private val vectors: Vectors = Json { ignoreUnknownKeys = true }
        .decodeFromString<Root>(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json"))
        .projectLabelFilter

    private val labels = vectors.labels.map { Label(id = it.id, title = it.title) }
    private val parents = vectors.tasks.associate { it.id to it.parent }
    private val tasks = vectors.tasks.map { task ->
        Task(id = task.id, title = task.title, labels = task.labels.map { id -> labels.first { it.id == id } })
    }

    @Test
    fun theChoicesHold() {
        assertTrue(vectors.choices.isNotEmpty())
        for (vector in vectors.choices) {
            assertEquals(
                vector.expect,
                ProjectLabelFilter.choices(tasks, vector.picked.toSet(), labels).map { Count(it.label.id, it.count) },
                vector.name,
            )
        }
    }

    @Test
    fun theShownTasksHold() {
        assertTrue(vectors.shown.isNotEmpty())
        for (vector in vectors.shown) {
            assertEquals(
                vector.expect,
                ProjectLabelFilter.shown(tasks, vector.picked.toSet()) { parents[it.id] }.map { it.id },
                vector.name,
            )
        }
    }
}
