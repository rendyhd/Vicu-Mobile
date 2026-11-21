package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.pro.ProFixture.day
import com.rendyhd.vicu.util.pro.ProFixture.flag
import com.rendyhd.vicu.util.pro.ProFixture.forEachZone
import com.rendyhd.vicu.util.pro.ProFixture.iso
import com.rendyhd.vicu.util.pro.ProFixture.ledgerOf
import com.rendyhd.vicu.util.pro.ProFixture.objects
import com.rendyhd.vicu.util.pro.ProFixture.pro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `pro.plans`, `pro.topics` and `pro.logKinds` vectors (contract sections 21.16, 21.17, 21.19,
 * 21.20 and 21.21), in the contract's zones. The desktop runs the same (`pro-suite.ts`, the
 * describes "90-day plans", "1:1 topics" and "blockers, commitments and stops").
 */
class ProPlansFixtureTest {

    private fun longs(values: List<Long>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun texts(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*fields))
    private fun nullable(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
    private fun nullable(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
    private fun strings(element: JsonElement?): List<String> = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** A task of these blocks: `id`, `title`, `parent`, `due_date`, `done`, `done_at`, `labels`, `created`. */
    private fun task(raw: JsonObject, zone: TimeZone): Task {
        val parent = raw.whole("parent")
        return Task(
            id = raw.getValue("id").jsonPrimitive.long,
            title = raw.text("title").orEmpty(),
            done = raw.flag("done"),
            doneAt = iso(raw.text("done_at"), zone).orEmpty(),
            dueDate = iso(raw.text("due_date"), zone) ?: ProFixture.NULL_DATE,
            created = iso(raw.text("created"), zone).orEmpty(),
            labels = (raw["labels"] as? JsonArray).orEmpty().map { Label(id = it.jsonObject.getValue("id").jsonPrimitive.long, title = "") },
            relatedTasks = if (parent != null) mapOf("parenttask" to listOf(Task(id = parent, title = ""))) else emptyMap(),
        )
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    // --- 90-day plans ---------------------------------------------------------------------------

    private val p: JsonObject = pro.getValue("plans").jsonObject
    private val plan: ProjectPlan = ProjectPlans.readPlan(p.getValue("plan"))!!

    private fun weekJson(week: PlanWeek) = obj("from" to JsonPrimitive(week.from.toString()), "to" to JsonPrimitive(week.to.toString()))

    private fun cellJson(cell: PlanCell) = obj(
        "due" to JsonPrimitive(cell.due),
        "delivered" to JsonPrimitive(cell.delivered),
        "late" to JsonPrimitive(cell.late),
        "slipped" to JsonPrimitive(cell.slipped),
        "dropped" to JsonPrimitive(cell.dropped),
    )

    private fun viewJson(view: PlanView) = obj(
        "weeks" to JsonArray(view.weeks.map(::weekJson)),
        "week" to nullable(view.week),
        "items" to JsonArray(
            view.items.map { item ->
                obj(
                    "id" to JsonPrimitive(item.id),
                    "owner" to nullable(item.owner),
                    "done" to JsonPrimitive(item.done),
                    "total" to JsonPrimitive(item.total),
                    "delivered" to JsonPrimitive(item.delivered),
                    "dropped" to JsonPrimitive(item.dropped),
                    "overdue" to JsonPrimitive(item.overdue),
                    "next" to nullable(item.next),
                    "milestones" to longs(item.milestones),
                    "weeks" to JsonArray(item.weeks.map(::cellJson)),
                )
            },
        ),
        "total" to JsonPrimitive(view.total),
        "delivered" to JsonPrimitive(view.delivered),
        "overdue" to JsonPrimitive(view.overdue),
    )

    @Test
    fun aPlanIsReadFromItsTwoDays() {
        for (case in p.objects("read")) {
            val read = ProjectPlans.readPlan(case["value"])
            assertEquals(case["expect"] ?: JsonNull, read?.let(ProjectPlans::planFields) ?: JsonNull, case.toString())
        }
        val stored = p.getValue("stored").jsonObject
        assertEquals(stored.getValue("expect"), ProjectPlans.planValue(ProjectPlans.readPlan(stored.getValue("plan"))!!))
        for (case in p.objects("defaultEnd")) {
            assertEquals(day(case.text("expect")!!), ProjectPlans.defaultPlanEnd(day(case.text("start")!!)), case.toString())
        }
    }

    @Test
    fun weeksRunFromTheFirstDay() {
        for (case in p.objects("weeks")) {
            val range = case.getValue("plan").jsonObject
            val weeks = ProjectPlans.planWeeks(day(range.text("start")!!), day(range.text("end")!!))
            val expected = case["expect"]
            if (expected != null) {
                assertEquals(expected, JsonArray(weeks.map(::weekJson)), case.toString())
            } else {
                assertEquals(case.getValue("count").jsonPrimitive.int, weeks.size, case.toString())
                assertEquals(case.getValue("last"), weekJson(weeks.last()), case.toString())
            }
        }
        for (case in p.objects("weekOf")) {
            val expected = (case["expect"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
            assertEquals(expected, ProjectPlans.planWeekOf(plan, day(case.text("day")!!)), case.toString())
        }
    }

    @Test
    fun thePlansOfAnAccountOpenOnesFirst() {
        val list = p.getValue("list").jsonObject
        val values = list.getValue("values").jsonObject.toMap()
        assertEquals(list.getValue("expect").jsonArray.map { it.jsonPrimitive.long }, ProjectPlans.plansOf(values).map { it.project })
        assertTrue(ProPrefs.isProKey("project_plan.30"))
        assertFalse(ProPrefs.isProKey("project_plan.x"))
    }

    @Test
    fun theViewIsCountedFromTasksAndVerdicts() = forEachZone { zone ->
        val tasks = p.objects("tasks").map { task(it, zone) }
        val ledger = ledgerOf(p.getValue("ledger").jsonObject)
        assertEquals(p.getValue("view"), viewJson(ProjectPlans.planView(plan, tasks, ledger, day(p.text("today")!!), zone)))
        for (case in p.objects("beforeAndAfter")) {
            val view = ProjectPlans.planView(plan, tasks, ledger, day(case.text("today")!!), zone)
            val week = (case["week"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
            assertEquals(week, view.week, case.toString())
            (case["overdue"] as? JsonPrimitive)?.let { assertEquals(it.int, view.overdue, case.toString()) }
        }
    }

    @Test
    fun closingCarriesTheOpenWorkIntoTheNextPlan() = forEachZone { zone ->
        val stored = ProjectPlans.readPlan(p.getValue("stored").jsonObject.getValue("expect"))!!.copy(closed = null)
        for (case in p.objects("next")) {
            val next = ProjectPlans.nextPlan(stored, 30, day(case.text("today")!!))
            assertEquals(case.getValue("expect"), ProjectPlans.planFields(next), case.toString())
        }
        val carry = ProjectPlans.carryOver(p.objects("tasks").map { task(it, zone) })
        assertEquals(p.getValue("carryOver"), obj("items" to longs(carry.items), "milestones" to longs(carry.milestones)))
        assertEquals(day("2026-12-01"), ProjectPlans.closePlan(stored, day("2026-12-01")).closed)
    }

    @Test
    fun aPlanIsWritableOnlyWhenItReads() {
        val stored = p.getValue("stored").jsonObject.getValue("plan")
        assertTrue(SyncedPrefKeys.isWritableProValue("project_plan.30", stored))
        assertTrue(SyncedPrefKeys.isWritableProValue("project_plan.30", JsonNull))
        assertFalse(SyncedPrefKeys.isWritableProValue("project_plan.30", obj("start" to JsonPrimitive("2026-11-16"))))
        assertTrue(SyncedPrefKeys.isWritableProValue("label_kind.90", JsonPrimitive("private")))
    }

    // --- 1:1 topics -----------------------------------------------------------------------------

    @Test
    fun privateLabels() {
        val s = pro.getValue("topics").jsonObject
        assertEquals(
            s.getValue("privateLabels").jsonArray.map { it.jsonPrimitive.long },
            ProPrefs.privateLabelIds(s.getValue("prefs").jsonObject.toMap()).sorted(),
        )
    }

    @Test
    fun theTopicsOfAPerson() = forEachZone { zone ->
        val s = pro.getValue("topics").jsonObject
        val tasks = s.objects("tasks").map { task(it, zone) }
        val ledger = ledgerOf(s.getValue("ledger").jsonObject)
        val privateLabels = ProPrefs.privateLabelIds(s.getValue("prefs").jsonObject.toMap())
        for (case in s.objects("cases")) {
            val person = case.getValue("person").jsonPrimitive.long
            val topics = Topics.personTopics(person, tasks, ledger, day(s.text("today")!!), zone, privateLabels)
            assertEquals(
                case.getValue("expect"),
                obj("now" to longs(topics.now), "later" to longs(topics.later), "hidden" to longs(topics.hidden)),
                "person $person",
            )
        }
    }

    // --- Blockers, commitments and stops --------------------------------------------------------

    private val k: JsonObject = pro.getValue("logKinds").jsonObject
    private fun kindsLedger(): ProLedger = ledgerOf(k.getValue("ledger").jsonObject)

    @Test
    fun theyStayOpenUntilResolved() = forEachZone { zone ->
        for (case in k.objects("closed")) {
            val closed = LedgerRules.closedOn("log", case.getValue("value").jsonObject, zone)
            assertEquals(case.text("expect"), closed?.toString(), case.toString())
        }
        for (case in k.objects("open")) {
            val open = Groups.openLog(kindsLedger(), case.getValue("label").jsonPrimitive.long, case.text("kind")!!)
            assertEquals(strings(case["expect"]), open.map { it.id }, case.toString())
        }
    }

    @Test
    fun aSessionHoldsWhatWasCapturedInIt() {
        val session = k.getValue("session").jsonObject
        val roundId = session.text("round")!!
        val round = k.getValue("ledger").jsonObject.getValue("rounds").jsonObject.getValue(roundId).jsonObject
        val ledger = kindsLedger()
        val group = Groups.groupsOf(ledger).first { it.id == round.text("meeting") }
        val contents = Sessions.sessionContents(roundId, round.text("started")!!, round.text("ended")!!, group, emptyList(), ledger)
        val json = obj(
            "verdicts" to JsonArray(contents.verdicts.map { obj("taskId" to JsonPrimitive(it.taskId), "verdict" to it.verdict) }),
            "created" to longs(contents.created),
            "decisions" to texts(contents.decisions),
            "questions" to texts(contents.questions),
            "issues" to texts(contents.issues),
            "solved" to texts(contents.solved),
            "blockers" to texts(contents.blockers),
            "commitments" to texts(contents.commitments),
            "stops" to texts(contents.stops),
        )
        assertEquals(session.getValue("expect"), json)
    }

    @Test
    fun theirSentencesAreInTheMessages() {
        val messages = pro.getValue("documents").jsonObject.getValue("messages").jsonObject
        for ((key, text) in k.getValue("messages").jsonObject) assertEquals(text, messages[key], key)
    }

    private fun docLineJson(line: DocLine): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("key" to JsonPrimitive(line.key))
        if (line.params.isNotEmpty()) {
            fields["params"] = JsonObject(
                line.params.mapValues { (_, value) ->
                    when (value) {
                        is DocParam.Text -> JsonPrimitive(value.value)
                        is DocParam.Number -> JsonPrimitive(value.value)
                        is DocParam.Day -> obj("day" to JsonPrimitive(value.day.toString()))
                    }
                },
            )
        }
        return JsonObject(fields)
    }

    private fun documentJson(document: ProDocument): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        document.title?.let { fields["title"] = docLineJson(it) }
        fields["blocks"] = JsonArray(
            document.blocks.map { block ->
                val parts = linkedMapOf<String, JsonElement>()
                block.heading?.let { parts["heading"] = docLineJson(it) }
                block.text?.let { parts["text"] = docLineJson(it) }
                if (block.items.isNotEmpty()) parts["items"] = JsonArray(block.items.map(::docLineJson))
                JsonObject(parts)
            },
        )
        return JsonObject(fields)
    }

    @Test
    fun theDocumentsNameThem() = forEachZone { zone ->
        val d = pro.getValue("documents").jsonObject
        val t = Documents.messageFormatter(d.getValue("messages").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val today = day(k.text("today")!!)
        val fmt = DateDisplayFormat("en-GB", hour12 = false)
        val formatDay = { date: LocalDate -> DateDisplay.formatWeekdayDate(date, today, fmt) }
        val names = DocNames(
            person = { id -> k.getValue("names").jsonObject[id.toString()]?.jsonPrimitive?.content ?: "?" },
            me = k.text("me")!!,
        )
        val ledger = kindsLedger()
        val group = Groups.groupsOf(ledger).first()
        val round = k.getValue("ledger").jsonObject.getValue("rounds").jsonObject.getValue("r9").jsonObject
        for (case in k.objects("documents")) {
            val name = case.text("name")
            val document = when (case.text("type")) {
                "agenda" -> Documents.agendaDocument(
                    group = group,
                    kind = case.text("kind") ?: "weekly",
                    date = today,
                    tasks = emptyList(),
                    ledger = ledger,
                    names = names,
                    today = today,
                    weekStart = WeekStart.fromKey("monday"),
                    zone = zone,
                )
                "note" -> Documents.weeklyNoteDocument(
                    listOf(group), emptyList(), ledger, day(case.text("from")!!), day(case.text("to")!!), names, zone,
                )
                else -> {
                    val recap = Documents.recapDocument(
                        group = group,
                        kind = round.text("kind")!!,
                        date = day(round.text("at")!!),
                        round = "r9",
                        started = round.text("started")!!,
                        ended = round.text("ended")!!,
                        tasks = emptyList(),
                        ledger = ledger,
                        names = names,
                        zone = zone,
                        absent = emptyList(),
                    )
                    case["decisionIds"]?.let { assertEquals(strings(it), recap.decisionIds, "$name: decision ids") }
                    recap.document
                }
            }
            assertEquals(case.getValue("document"), documentJson(document), "$name: document")
            val rendered = Documents.renderDocument(document, t, formatDay)
            assertEquals(strings(case["text"]), rendered.text.split("\n"), "$name: text")
            assertEquals(case.text("html"), rendered.html, "$name: html")
            assertEquals(strings(case["markdown"]), rendered.markdown.split("\n"), "$name: markdown")
        }
    }
}
