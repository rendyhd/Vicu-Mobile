package com.rendyhd.vicu.util.pro

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.WeekStart
import com.rendyhd.vicu.util.pro.ProFixture.day
import com.rendyhd.vicu.util.pro.ProFixture.flag
import com.rendyhd.vicu.util.pro.ProFixture.forEachZone
import com.rendyhd.vicu.util.pro.ProFixture.intOf
import com.rendyhd.vicu.util.pro.ProFixture.iso
import com.rendyhd.vicu.util.pro.ProFixture.ledgerOf
import com.rendyhd.vicu.util.pro.ProFixture.objects
import com.rendyhd.vicu.util.pro.ProFixture.pro
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * The `pro` vectors for groups, sessions, follow-ups and text for other people (contract sections
 * 21.6, 21.7, 21.10, 21.11 and 21.16 to 21.19), in the contract's zones. The desktop runs the same
 * (`pro-suite.ts`, from "Groups, sessions, follow-ups and text for other people" on, and `capOff`).
 */
class ProGroupsFixtureTest {

    // --- JSON forms of the results, to compare with the vectors ----------------------------------

    private fun longs(values: List<Long>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun texts(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*fields))
    private fun nullable(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
    private fun nullable(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun requesterJson(requester: Requester): JsonObject = when (requester) {
        Requester.You -> obj("kind" to JsonPrimitive("you"))
        is Requester.Group -> obj("kind" to JsonPrimitive("group"), "group" to JsonPrimitive(requester.group))
        is Requester.Person -> obj("kind" to JsonPrimitive("person"), "person" to JsonPrimitive(requester.person))
        is Requester.Text -> obj("kind" to JsonPrimitive("text"), "text" to JsonPrimitive(requester.text))
    }

    private fun groupJson(group: Group): JsonObject = obj(
        "id" to JsonPrimitive(group.id),
        "label" to JsonPrimitive(group.label),
        "name" to JsonPrimitive(group.name),
        "people" to longs(group.people),
        "sessions" to JsonArray(group.sessions),
        "checks" to JsonPrimitive(group.checks),
        "language" to nullable(group.language),
    )

    private fun ownersJson(parts: List<OwnerActions>): JsonArray =
        JsonArray(parts.map { obj("owner" to nullable(it.owner), "tasks" to longs(it.tasks)) })

    private fun pageJson(page: GroupPage): JsonObject = obj(
        "since" to nullable(page.since),
        "yours" to longs(page.yours),
        "overdue" to ownersJson(page.overdue),
        "due" to ownersJson(page.due),
        "fresh" to ownersJson(page.fresh),
        "open" to ownersJson(page.open),
        "done" to ownersJson(page.done),
        "noOwner" to longs(page.noOwner),
    )

    private fun reliabilityJson(value: Reliability?): JsonElement = if (value == null) JsonNull else obj(
        "sessions" to JsonPrimitive(value.sessions),
        "from" to JsonPrimitive(value.from.toString()),
        "onTime" to JsonPrimitive(value.onTime),
        "late" to JsonPrimitive(value.late),
        "dropped" to JsonPrimitive(value.dropped),
        "takenOver" to JsonPrimitive(value.takenOver),
        "openPastDue" to JsonPrimitive(value.openPastDue),
        "total" to JsonPrimitive(value.total),
    )

    private fun personPageJson(page: PersonPage): JsonObject {
        fun parts(list: List<PersonPart>) = JsonArray(list.map { obj("group" to nullable(it.group), "tasks" to longs(it.tasks)) })
        return obj("toYou" to parts(page.toYou), "toOthers" to parts(page.toOthers))
    }

    private fun lineJson(line: FollowUpLine): JsonObject = when (line) {
        is FollowUpLine.Verdict -> obj(
            "kind" to JsonPrimitive("verdict"), "key" to JsonPrimitive(line.key), "taskId" to JsonPrimitive(line.taskId),
            "person" to nullable(line.person), "due" to JsonPrimitive(line.due.toString()),
        )
        is FollowUpLine.Check -> obj(
            "kind" to JsonPrimitive("check"), "key" to JsonPrimitive(line.key), "person" to JsonPrimitive(line.person),
            "tasks" to longs(line.tasks), "due" to JsonPrimitive(line.due.toString()),
        )
        is FollowUpLine.Bring -> obj(
            "kind" to JsonPrimitive("bring"), "key" to JsonPrimitive(line.key), "taskId" to JsonPrimitive(line.taskId),
            "person" to nullable(line.person), "checkOn" to JsonPrimitive(line.checkOn.toString()),
        )
        is FollowUpLine.Recap -> obj(
            "kind" to JsonPrimitive("recap"), "key" to JsonPrimitive(line.key), "round" to JsonPrimitive(line.round),
            "meeting" to JsonPrimitive(line.meeting), "ended" to JsonPrimitive(line.ended),
        )
        is FollowUpLine.Note -> obj("kind" to JsonPrimitive("note"), "key" to JsonPrimitive(line.key), "day" to JsonPrimitive(line.day.toString()))
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

    // --- The group world ----------------------------------------------------------------------

    private val w: JsonObject = pro.getValue("groupWorld").jsonObject
    private val worldValues: JsonObject = w.getValue("ledger").jsonObject
    private val today = day(w.text("today")!!)
    private val names = DocNames(
        person = { id -> w.getValue("names").jsonObject[id.toString()]?.jsonPrimitive?.content ?: "?" },
        me = w.text("me")!!,
    )

    private fun worldTask(raw: JsonObject, zone: TimeZone) = Task(
        id = raw.getValue("id").jsonPrimitive.long,
        title = raw.text("title").orEmpty(),
        done = raw.flag("done"),
        doneAt = iso(raw.text("done_at"), zone).orEmpty(),
        dueDate = iso(raw.text("due"), zone) ?: ProFixture.NULL_DATE,
        created = iso(raw.text("created"), zone).orEmpty(),
        labels = raw["labels"]?.jsonArray.orEmpty().map { Label(id = it.jsonPrimitive.long, title = "") },
    )

    private fun worldTasks(zone: TimeZone): List<Task> = w.objects("tasks").map { worldTask(it, zone) }

    /** The world's ledger, with collections left out ([without]) or changed ([change]) first. */
    private fun worldLedger(
        without: List<String> = emptyList(),
        change: (MutableMap<String, JsonElement>) -> Unit = {},
    ): ProLedger {
        val values = LinkedHashMap<String, JsonElement>(worldValues)
        for (key in without) values.remove(key)
        change(values)
        return ledgerOf(JsonObject(values))
    }

    private fun MutableMap<String, JsonElement>.changeCollection(key: String, change: (MutableMap<String, JsonElement>) -> Unit) {
        val records = LinkedHashMap<String, JsonElement>((this[key] as? JsonObject).orEmpty())
        change(records)
        this[key] = JsonObject(records)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun groupIn(ledger: ProLedger, id: String): Group = Groups.groupsOf(ledger).first { it.id == id }

    private fun strings(element: JsonElement?): List<String> = element?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    // --- The cap and the ledger -----------------------------------------------------------------

    @Test
    fun noLimitOnTheCap() {
        val vector = pro.getValue("capOff").jsonObject
        val base = vector.getValue("ledger").jsonObject
        fun withSettings(settings: JsonElement) = ledgerOf(JsonObject(base + ("settings" to settings)))
        for (case in vector.objects("cases")) {
            val expected = case.getValue("expect").jsonObject
            assertEquals(
                CapState(expected.intOf("active"), expected.intOf("cap"), expected.flag("agreed"), expected.flag("full"), expected.flag("unlimited")),
                Portfolio.capState(withSettings(case.getValue("settings"))),
                case.getValue("settings").toString(),
            )
        }
        assertTrue(Portfolio.overCap(Portfolio.capState(withSettings(obj("cap" to JsonPrimitive(2))))))
        assertFalse(Portfolio.overCap(Portfolio.capState(withSettings(obj("cap" to JsonPrimitive(2), "cap_off" to JsonPrimitive(true))))))
    }

    @Test
    fun mergingGroupRecords() {
        for (case in pro.getValue("ledgerMergeGroups").jsonArray.map { it.jsonObject }) {
            val merged = LedgerRules.merge(
                ProFixture.compactLedger(case.getValue("local").jsonObject),
                ProFixture.compactLedger(case.getValue("server").jsonObject),
            )
            assertEquals(ProFixture.compactLedger(case.getValue("expect").jsonObject), merged, case.text("name"))
        }
    }

    @Test
    fun aGroupsRecordsMoveAfter90Days() = forEachZone { zone ->
        val vector = pro.getValue("archiveGroups").jsonObject
        val plan = LedgerRules.archivePlan(ledgerOf(vector.getValue("ledger").jsonObject), day(vector.text("today")!!), zone)
        val parts = JsonObject(
            plan.parts.mapValues { (_, collections) ->
                JsonObject(collections.mapValues { (_, byId) -> JsonArray(byId.keys.sorted().map(::JsonPrimitive)) })
            },
        )
        assertEquals(vector.getValue("parts"), parts)
        assertEquals(strings(vector["moved"]), plan.moved.map { "${it.collection}/${it.id}" })
    }

    @Test
    fun whoAPromiseWasMadeTo() {
        for (case in pro.getValue("requester").jsonArray.map { it.jsonObject }) {
            val promise = case.getValue("promise").jsonObject
            assertEquals(case.getValue("expect"), requesterJson(Promises.requesterOf(promise)), promise.toString())
        }
    }

    // --- Working days and the weekly note -------------------------------------------------------

    @Test
    fun workingDays() {
        val vector = pro.getValue("workingDays").jsonObject
        for (case in vector.objects("before")) {
            val workdays = case["workdays"]?.let { WorkingDays.readWorkdays(it) } ?: WorkingDays.DEFAULT_WORKDAYS
            assertEquals(
                day(case.text("expect")!!),
                WorkingDays.workingDaysBefore(day(case.text("day")!!), case.intOf("n"), workdays),
                case.toString(),
            )
        }
        for (case in vector.objects("read")) {
            assertEquals(case.getValue("expect").jsonArray.map { it.jsonPrimitive.int }, WorkingDays.readWorkdays(case["value"]), case.toString())
        }
        for (case in vector.objects("checkDays")) {
            assertEquals(case.intOf("expect"), WorkingDays.readCheckDays(case["value"]), case.toString())
        }
    }

    @Test
    fun theWeeklyNoteAndStepsMarkedDone() = forEachZone { zone ->
        val vector = pro.getValue("weeklyNote").jsonObject
        for (case in vector.objects("due")) {
            val due = FollowUps.noteDue(case["settings"] as? JsonObject, day(case.text("today")!!), zone)
            assertEquals(case.text("expect"), due?.toString(), case.text("name"))
        }
        for (case in vector.objects("period")) {
            val period = FollowUps.notePeriod(case["settings"] as? JsonObject, day(case.text("today")!!), zone)
            val expected = case.getValue("expect").jsonObject
            assertEquals(NotePeriod(day(expected.text("from")!!), day(expected.text("to")!!)), period, case.text("name"))
        }
        for (case in vector.objects("done")) {
            assertEquals(strings(case["expect"]), Weekly.weeklyDoneSteps(case["settings"] as? JsonObject, day(case.text("week")!!)), case.text("name"))
        }
        for (case in vector.objects("mark")) {
            val marked = Weekly.withWeeklyDone(case["settings"] as? JsonObject, day(case.text("week")!!), case.text("step")!!)
            assertEquals(case.getValue("expect"), marked, case.text("name"))
        }
    }

    // --- Groups --------------------------------------------------------------------------------

    @Test
    fun theGroupsOfALedger() {
        assertEquals(pro.getValue("groups"), JsonArray(Groups.groupsOf(worldLedger()).map(::groupJson)))
    }

    @Test
    fun groupPages() = forEachZone { zone ->
        val tasks = worldTasks(zone)
        for (case in pro.getValue("groupPage").jsonArray.map { it.jsonObject }) {
            val ledger = worldLedger(without = strings(case["without"]))
            val page = Groups.groupPage(groupIn(ledger, case.text("group")!!), tasks, ledger, today, zone)
            assertEquals(case.getValue("expect"), pageJson(page), case.text("name"))
        }
    }

    @Test
    fun reliability() = forEachZone { zone ->
        val tasks = worldTasks(zone)
        for (case in pro.getValue("reliability").jsonArray.map { it.jsonObject }) {
            val drop = strings(case["dropRounds"])
            val ledger = worldLedger(without = strings(case["without"])) { values ->
                if (drop.isNotEmpty()) values.changeCollection("rounds") { rounds -> drop.forEach { rounds.remove(it) } }
            }
            val counts = Groups.reliability(groupIn(ledger, case.text("group")!!), tasks, ledger, today, zone)
            assertEquals(case.getValue("expect"), reliabilityJson(counts), case.text("name"))
        }
    }

    @Test
    fun aSecondMissAddsAnIssue() {
        for (case in pro.getValue("missIssue").jsonArray.map { it.jsonObject }) {
            val taskId = case.getValue("task").jsonPrimitive.long
            val promise = case["promise"] as? JsonObject
            val ledger = worldLedger { values ->
                if (promise != null) values.changeCollection("promises") { it[taskId.toString()] = promise }
            }
            val title = w.objects("tasks").first { it.getValue("id").jsonPrimitive.long == taskId }.text("title").orEmpty()
            val issue = Groups.missIssue(ledger, taskId, title, case.getValue("label").jsonPrimitive.long, today, case.intOf("threshold"))
            assertEquals(case.getValue("expect"), issue ?: JsonNull, case.text("name"))
        }
    }

    @Test
    fun aPersonsPage() = forEachZone { zone ->
        val ledger = worldLedger()
        val labels = Groups.groupsOf(ledger).map { it.label }.toSet()
        val tasks = worldTasks(zone)
        for (case in pro.getValue("personPage").jsonArray.map { it.jsonObject }) {
            val person = case.getValue("person").jsonPrimitive.long
            assertEquals(case.getValue("expect"), personPageJson(Groups.personPage(person, tasks, ledger, labels, zone)), "person $person")
        }
    }

    // --- Sessions ------------------------------------------------------------------------------

    @Test
    fun sessionStepsKindsAndScores() {
        val s = pro.getValue("sessions").jsonObject
        val steps = JsonObject(Sessions.SESSION_STEPS.entries.associate { (kind, list) -> kind.key to texts(list.map { it.key }) })
        assertEquals(s.getValue("steps"), steps)
        for (case in s.objects("kinds")) {
            assertEquals(case.text("expect"), Sessions.sessionKindOf(case["kind"].asText()).key, case.toString())
        }
        for (case in s.objects("scores")) {
            val expected = (case["expect"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int
            assertEquals(expected, Sessions.readScore(case["value"]), case.toString())
        }
    }

    @Test
    fun theActionsStep() = forEachZone { zone ->
        val ledger = worldLedger()
        val tasks = worldTasks(zone)
        for (case in pro.getValue("sessions").jsonObject.objects("actions")) {
            val actions = Sessions.sessionActions(
                group = groupIn(ledger, case.text("group")!!),
                tasks = tasks,
                ledger = ledger,
                today = today,
                kind = Sessions.sessionKindOf(case.text("kind")),
                weekStart = WeekStart.fromKey(case.text("weekStart")),
                zone = zone,
            )
            assertEquals(case.getValue("expect"), obj("verdicts" to longs(actions.verdicts), "others" to longs(actions.others)), case.text("name"))
        }
    }

    @Test
    fun whatASessionHeld() = forEachZone { zone ->
        val ledger = worldLedger()
        val tasks = worldTasks(zone)
        val rounds = worldValues.getValue("rounds").jsonObject
        for (case in pro.getValue("sessions").jsonObject.objects("contents")) {
            val roundId = case.text("round")!!
            val round = rounds.getValue(roundId).jsonObject
            val contents = Sessions.sessionContents(
                roundId, round.text("started")!!, round.text("ended")!!, groupIn(ledger, case.text("group")!!), tasks, ledger,
            )
            val json = obj(
                "verdicts" to JsonArray(contents.verdicts.map { obj("taskId" to JsonPrimitive(it.taskId), "verdict" to it.verdict) }),
                "created" to longs(contents.created),
                "decisions" to texts(contents.decisions),
                "questions" to texts(contents.questions),
                "issues" to texts(contents.issues),
                "solved" to texts(contents.solved),
            )
            assertEquals(case.getValue("expect"), json, roundId)
        }
    }

    @Test
    fun theRoundWrittenAtTheEnd() {
        for (case in pro.getValue("sessions").jsonObject.objects("end")) {
            val input = case.getValue("input").jsonObject
            val round = Sessions.endSession(
                meeting = input.text("meeting")!!,
                kind = input.text("kind")!!,
                at = day(input.text("at")!!),
                started = input.text("started")!!,
                ended = input.text("ended")!!,
                absent = input.getValue("absent").jsonArray.map { it.jsonPrimitive.long },
                readBack = input.flag("readBack"),
                score = (input["score"] as? JsonPrimitive)?.int,
            )
            assertEquals(case.getValue("expect"), round, input.toString())
        }
    }

    @Test
    fun recapMarksAndUndo() {
        val round = Sessions.endSession("g1", "weekly", day("2026-11-12"), "a", "b", emptyList(), readBack = true)
        val sent = Sessions.withRecapSent(Sessions.withRecapSent(round, "2026-11-12T10:00:00Z"), "2026-11-12T09:00:00Z")
        assertEquals(texts(listOf("2026-11-12T09:00:00Z", "2026-11-12T10:00:00Z")), sent["recap_sent"])
        assertNull(Sessions.withoutRecapSent(Sessions.withRecapSent(round, "x"), "x")["recap_sent"])
        val promise = obj("dir" to JsonPrimitive("theirs"), "person" to JsonPrimitive(40), "to" to obj("group" to JsonPrimitive(70)))
        val checked = Promises.withChecked(Promises.withChecked(promise, "2026-11-12T10:00:00Z"), "2026-11-11T10:00:00Z")
        assertEquals(texts(listOf("2026-11-11T10:00:00Z", "2026-11-12T10:00:00Z")), checked["checked"])
        assertEquals(texts(listOf("2026-11-11T10:00:00Z")), Promises.withoutMark(checked, "checked", "2026-11-12T10:00:00Z")["checked"])
        assertNull(Promises.withoutMark(Promises.withChecked(promise, "x"), "checked", "x")["checked"])
        assertEquals(promise["to"], checked["to"])
    }

    // --- Follow-ups ----------------------------------------------------------------------------

    @Test
    fun followUps() = forEachZone { zone ->
        val tasks = worldTasks(zone)
        for (case in pro.getValue("followUps").jsonArray.map { it.jsonObject }) {
            val ledger = if (case.flag("empty")) null else worldLedger { values ->
                case["settings"]?.let { values["settings"] = it }
                (case["meetings"] as? JsonObject)?.let { meetings -> values.changeCollection("meetings") { it.putAll(meetings) } }
            }
            val snoozes = (case["snoozes"] as? JsonObject)?.let { Promises.readVerdictSnoozes(it) }.orEmpty()
            val dismissed = (case["dismissed"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val lines = FollowUps.followUps(tasks, ledger, today, zone, snoozes, dismissed)
            assertEquals(case.getValue("expect"), JsonArray(lines.map(::lineJson)), case.text("name"))
        }
    }

    @Test
    fun pruningDismissals() {
        val kept = FollowUps.pruneDismissed(mapOf("a" to "2026-11-13", "b" to "2026-11-12", "c" to "2026-11-01"), day("2026-11-12"))
        assertEquals(mapOf("a" to "2026-11-13"), kept)
    }

    // --- Text for other people -----------------------------------------------------------------

    @Test
    fun textForOtherPeople() = forEachZone { zone ->
        val d = pro.getValue("documents").jsonObject
        val t = Documents.messageFormatter(d.getValue("messages").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val fmt = DateDisplayFormat(d.text("locale")!!, hour12 = false)
        val formatDay = { date: kotlinx.datetime.LocalDate -> DateDisplay.formatWeekdayDate(date, today, fmt) }
        val ledger = worldLedger()
        val tasks = worldTasks(zone)
        val meetings = worldValues.getValue("meetings").jsonObject
        val rounds = worldValues.getValue("rounds").jsonObject
        for (case in d.objects("cases")) {
            val name = case.text("name")
            val emptyWorld = case.flag("emptyWorld")
            val document: ProDocument? = when (case.text("type")) {
                "agenda" -> Documents.agendaDocument(
                    group = groupIn(ledger, case.text("group")!!),
                    kind = case.text("kind")!!,
                    date = day(case.text("date")!!),
                    tasks = tasks,
                    ledger = ledger,
                    names = names,
                    today = today,
                    weekStart = WeekStart.fromKey(case.text("weekStart")),
                    zone = zone,
                )
                "check" -> Documents.checkDocument(
                    names.person(case.getValue("person").jsonPrimitive.long),
                    case.objects("items").map { CheckItem(it.text("title")!!, it.text("due")?.let(::day)) },
                )
                "note" -> {
                    val groups = strings(case["groups"]).map { groupIn(ledger, it) }
                    if (emptyWorld) {
                        Documents.weeklyNoteDocument(
                            groups, emptyList(), ledgerOf(obj("meetings" to meetings)),
                            day(case.text("from")!!), day(case.text("to")!!), names, zone,
                        )
                    } else {
                        Documents.weeklyNoteDocument(groups, tasks, ledger, day(case.text("from")!!), day(case.text("to")!!), names, zone)
                    }
                }
                else -> {
                    val groupId = case.text("group")!!
                    val roundId = case.text("round")!!
                    val round = rounds.getValue(roundId).jsonObject
                    val quiet = if (emptyWorld) {
                        ledgerOf(obj("meetings" to obj(groupId to meetings.getValue(groupId)), "rounds" to obj(roundId to round)))
                    } else {
                        ledger
                    }
                    val group = groupIn(ledger, groupId)
                    val sessionTasks = if (emptyWorld) emptyList() else tasks
                    val date = day(round.text("at")!!)
                    if (case.text("type") == "readback") {
                        Documents.readBackDocument(
                            group, date, roundId, round.text("started")!!, round.text("ended")!!, sessionTasks, quiet, names, zone,
                        )
                    } else {
                        val recap = Documents.recapDocument(
                            group = group,
                            kind = round.text("kind")!!,
                            date = date,
                            round = roundId,
                            started = round.text("started")!!,
                            ended = round.text("ended")!!,
                            tasks = sessionTasks,
                            ledger = quiet,
                            names = names,
                            zone = zone,
                            absent = if (emptyWorld) emptyList() else round.getValue("absent").jsonArray.map { it.jsonPrimitive.long },
                        )
                        assertEquals(case.getValue("taskIds").jsonArray.map { it.jsonPrimitive.long }, recap.taskIds, "$name: task ids")
                        assertEquals(strings(case["decisionIds"]), recap.decisionIds, "$name: decision ids")
                        recap.document
                    }
                }
            }
            requireNotNull(document) { "$name: no document" }
            assertEquals(case.getValue("document"), documentJson(document), "$name: document")
            val rendered = Documents.renderDocument(document, t, formatDay)
            assertEquals(strings(case["text"]), rendered.text.split("\n"), "$name: text")
            assertEquals(case.text("html"), rendered.html, "$name: html")
            assertEquals(strings(case["markdown"]), rendered.markdown.split("\n"), "$name: markdown")
        }
    }

    @Test
    fun theLanguageOfAText() {
        for (case in pro.getValue("documents").jsonObject.objects("language")) {
            val settings = obj("text_language" to (case["setting"] ?: JsonNull))
            assertEquals(case.text("expect"), Documents.textLanguage(case["group"].asText(), settings), case.toString())
        }
    }

    @Test
    fun aKeyWithoutATextIsShownAsTheKey() {
        assertEquals("note.nothing", Documents.messageFormatter(emptyMap())("note.nothing", emptyMap()))
    }

    @Test
    fun groupLabelsAreReadFromTheKeys() {
        val values = mapOf<String, JsonElement?>(
            "label_kind.70" to JsonPrimitive("group"),
            "label_kind.40" to JsonPrimitive("person"),
            "label_kind.x" to JsonPrimitive("group"),
        )
        assertEquals(setOf(70L), ProPrefs.groupLabelIds(values))
        assertTrue(ProPrefs.isGroupLabel(values, 70))
        assertFalse(ProPrefs.isGroupLabel(values, 40))
    }
}
