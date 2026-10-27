package com.rendyhd.vicu.util

import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.domain.model.Task
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `guided` vectors of test-fixtures/cross-app-semantics-v1.json (contract section 14), in each
 * contract zone. The desktop runs the same file.
 */
class GuidedFixtureTest {

    @Serializable
    private data class FixtureProject(
        val id: Long,
        val title: String,
        val parent: Long = 0,
        val archived: Boolean = false,
        val permission: Int = 2,
        val review: JsonElement = JsonPrimitive("never"),
    )

    @Serializable
    private data class FixtureTask(
        val id: Long,
        val title: String,
        val project: Long,
        val done: Boolean = false,
        val due: String? = null,
        val due_time: String? = null,
        val created: String? = null,
        val updated: String? = null,
        val done_at: String? = null,
        val repeats: Boolean = false,
        val position: Double = 0.0,
        val hidden: Boolean = false,
    )

    @Serializable
    private data class ReviewSettings(val enabled: Boolean, val defaultCadence: Int, val excludeInbox: Boolean)

    @Serializable
    private data class World(
        val today: String,
        val inboxProjectId: Long,
        val reviewSettings: ReviewSettings,
        val projects: List<FixtureProject>,
        val tasks: List<FixtureTask>,
    )

    @Serializable
    private data class Health(
        val project: Long,
        val open: Int,
        val untouched: Int,
        val overdue: Int,
        val scheduled: Boolean,
        val last_done_days: Int?,
        val untouched_ids: List<Long>,
        val first_open: Long?,
        val line: String,
    )

    @Serializable
    private data class Step(val id: String, val count: Int?, val ids: List<Long>? = null)

    @Serializable
    private data class Item(val id: String, val count: Int?, val detail: String?)

    @Serializable
    private data class AgendaVector(val name: String, val weeklyReviewLast: String?, val expect: List<Item>)

    @Serializable
    private data class Ago(val days: Int, val expect: String)

    @Serializable
    private data class Block(
        val limits: Map<String, Int>,
        val world: World,
        val queues: Map<String, List<Long>>,
        val health: List<Health>,
        val weekly: List<Step>,
        val agenda: List<AgendaVector>,
        val ago: List<Ago>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val block: Block by lazy {
        val root = json.parseToJsonElement(CrossAppFixture.readTestFixture("cross-app-semantics-v1.json")).jsonObject
        json.decodeFromJsonElement(Block.serializer(), root.getValue("guided"))
    }

    private fun world(zone: TimeZone): GuidedWorld {
        val w = block.world
        fun instant(value: String?) = value?.let { CrossAppFixture.local(it, zone).toString() }.orEmpty()
        val projects = w.projects.map { p ->
            val meta = when (val review = p.review) {
                is JsonPrimitive -> when (review.content) {
                    "excluded" -> ReviewMetadata(ReviewState.EXCLUDED, null, null)
                    else -> ReviewMetadata(ReviewState.NEVER, null, null)
                }
                is JsonObject -> ReviewMetadata(
                    ReviewState.REVIEWED,
                    review.getValue("last").jsonPrimitive.content,
                    review["cadence"]?.jsonPrimitive?.int,
                )
                else -> error("review ${p.id}")
            }
            Project(
                id = p.id,
                title = p.title,
                description = if (meta.state == ReviewState.NEVER) "" else ReviewMetadata.upsert("", meta),
                parentProjectId = p.parent,
                isArchived = p.archived,
                maxPermission = p.permission,
            )
        }
        val tasks = w.tasks.map { t ->
            val due = t.due?.let { date ->
                if (t.due_time == null) DueDates.dateOnlyDue(CrossAppFixture.date(date), zone).toString()
                else CrossAppFixture.local("${date}T${t.due_time}:00", zone).toString()
            }.orEmpty()
            Task(
                id = t.id,
                title = t.title,
                description = if (t.hidden) "<!-- vicu-prefs:v1:e30 -->" else "",
                done = t.done,
                doneAt = instant(t.done_at),
                dueDate = due,
                projectId = t.project,
                repeatAfter = if (t.repeats) 86_400L else 0L,
                position = t.position,
                created = instant(t.created),
                updated = instant(t.updated),
            )
        }
        return GuidedWorld(
            tasks = tasks,
            projects = projects,
            inboxId = w.inboxProjectId,
            today = CrossAppFixture.date(w.today),
            zone = zone,
            reviewEnabled = w.reviewSettings.enabled,
            reviewCadenceDays = w.reviewSettings.defaultCadence,
            reviewExcludeInbox = w.reviewSettings.excludeInbox,
        )
    }

    @Test
    fun limitsMatchTheFixture() {
        assertEquals(
            mapOf(
                "waiting_days" to Guided.WAITING_DAYS,
                "waiting_max" to Guided.WAITING_MAX,
                "review_untouched_days" to Guided.REVIEW_UNTOUCHED_DAYS,
                "look_ahead_days" to Guided.LOOK_AHEAD_DAYS,
                "weekly_due_days" to Guided.WEEKLY_DUE_DAYS,
                "inbox_age_days" to Guided.INBOX_AGE_DAYS,
            ),
            block.limits,
        )
    }

    @Test
    fun queues() {
        for (zone in CrossAppFixture.zones) {
            val world = world(zone)
            assertEquals(block.queues.getValue("inbox"), Guided.inboxQueue(world), "inbox in $zone")
            assertEquals(block.queues.getValue("overdue"), Guided.overdueQueue(world), "overdue in $zone")
            assertEquals(block.queues.getValue("waiting"), Guided.waitingQueue(world), "waiting in $zone")
            assertEquals(block.queues.getValue("review"), Guided.reviewQueue(world), "review in $zone")
        }
    }

    @Test
    fun health() {
        for (zone in CrossAppFixture.zones) {
            val world = world(zone)
            for (v in block.health) {
                val h = Guided.health(world, v.project)
                val actual = Health(v.project, h.open, h.untouched, h.overdue, h.scheduled, h.lastDoneDays, h.untouchedIds, h.firstOpen, h.line)
                assertEquals(v, actual, "project ${v.project} in $zone")
            }
        }
    }

    @Test
    fun weeklySteps() {
        for (zone in CrossAppFixture.zones) {
            val actual = Guided.weeklySteps(world(zone)).map { step ->
                Step(step.id, step.count, step.ids.takeIf { step.id == Guided.STEP_DONE || step.id == Guided.STEP_UPCOMING })
            }
            assertEquals(block.weekly, actual, "in $zone")
        }
    }

    @Test
    fun agenda() {
        for (zone in CrossAppFixture.zones) {
            val world = world(zone)
            for (v in block.agenda) {
                val actual = Guided.agenda(world, v.weeklyReviewLast).map { Item(it.id, it.count, it.detail) }
                assertEquals(v.expect, actual, "${v.name} in $zone")
            }
        }
    }

    @Test
    fun ago() {
        for (v in block.ago) assertEquals(v.expect, Guided.ago(v.days), "${v.days} days")
    }
}
