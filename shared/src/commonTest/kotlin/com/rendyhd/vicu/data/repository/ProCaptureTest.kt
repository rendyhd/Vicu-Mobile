package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.ui.FakeLabelRepository
import com.rendyhd.vicu.ui.screens.taskdetail.applyTaskEditShortcuts
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TaskParser
import com.rendyhd.vicu.util.pro.Person
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quick add with Pro's phrases (cross-app semantics 5.5): read and written only while Pro is on. */
class ProCaptureTest {

    private val zone = TimeZone.of("Pacific/Auckland")

    /** Thursday 12 November 2026, 10:00 in Auckland. */
    private val now = LocalDateTime.parse("2026-11-12T10:00")
    private val jan = Person(id = 31, title = "Jan de Vries")
    private val on = ProCapture(on = true, people = listOf(jan))

    private fun parse(text: String, pro: ProCapture): ParseResult =
        TaskParser.parse(text, pro.config(ParserConfig()), reference = now, zone = zone, people = pro.parserPeople)

    @Test
    fun `off reads the text as before and writes nothing`() {
        val result = parse("Jan: send the plan starts monday !flag", ProCapture.OFF)

        assertFalse(ProCapture.OFF.config(ParserConfig()).pro)
        assertTrue("starts" in result.title && "!flag" in result.title && result.title.startsWith("Jan:"), result.title)
        val task = ProCapture.OFF.applyToNew(Task(id = 0, title = "x"), result, zone)
        assertEquals("", task.startDate)
        assertFalse(task.isFavorite)
        assertNull(ProCapture.OFF.personLabelOf(result))
    }

    @Test
    fun `on a start date a flag and a person`() {
        val result = parse("Jan: send the plan starts monday !flag", on)

        assertEquals("send the plan", result.title)
        val task = on.applyToNew(Task(id = 0, title = result.title), result, zone)
        // Monday 16 November, local midnight in Auckland.
        assertEquals("2026-11-15T11:00:00Z", task.startDate)
        assertEquals("", task.endDate)
        assertTrue(task.isFavorite)
        assertEquals(31L, on.personLabelOf(result))
    }

    @Test
    fun `a block holds the start so no lone start date is written`() {
        val result = parse("Write report starts monday at 9am for 30m", on)
        val blocked = Task(id = 0, title = "x", startDate = "2026-11-15T20:00:00Z", endDate = "2026-11-15T20:30:00Z")

        assertEquals("2026-11-15T20:00:00Z", on.applyToNew(blocked, result, zone).startDate)
        // A start date read alone does not go over a block chosen by hand either.
        val lone = parse("Write report starts monday", on)
        assertEquals("2026-11-15T20:00:00Z", on.applyToNew(blocked, lone, zone).startDate)
    }

    @Test
    fun `a person label that is no longer a person is not added`() {
        val result = parse("Jan: send the plan", on)
        assertNull(ProCapture(on = true).personLabelOf(result))
    }

    @Test
    fun `editing a title sets the start date and flag and adds the person`() {
        val original = Task(id = 7, title = "old")
        val result = parse("Jan: send the plan starts monday !flag", on)

        val edited = applyTaskEditShortcuts(original, result, emptyList(), zone, pro = on)

        assertEquals("2026-11-15T11:00:00Z", edited.task.startDate)
        assertTrue(edited.task.isFavorite)
        assertEquals(listOf("Jan de Vries"), edited.labelNames)

        // Off: the same text changes no field.
        val off = applyTaskEditShortcuts(original, parse("Jan: send the plan starts monday !flag", ProCapture.OFF), emptyList(), zone)
        assertEquals("", off.task.startDate)
        assertFalse(off.task.isFavorite)
        assertTrue(off.labelNames.isEmpty())
    }

    @Test
    fun `a person makes their promise for a person yours with the entry sheet's words`() {
        val theirs = on.promiseOf(parse("Jan: send the plan", on), words = "  I'll send it Friday ", doneWhen = "")
        assertEquals("""{"dir":"theirs","person":31,"words":"I'll send it Friday"}""", theirs.toString())

        val mine = on.promiseOf(parse("for Jan: review the deck", on), words = null, doneWhen = "Jan says it reads well")
        assertEquals("""{"dir":"mine","person":31,"done_when":"Jan says it reads well"}""", mine.toString())

        // No person, Pro off, or a label that is no longer a person: no promise.
        assertNull(on.promiseOf(parse("send the plan", on)))
        assertNull(ProCapture.OFF.promiseOf(parse("Jan: send the plan", ProCapture.OFF)))
        assertNull(ProCapture(on = true).promiseOf(parse("Jan: send the plan", on)))
    }

    private val priya = Person(id = 43, title = "Priya")

    /** Jan and Priya are people; labels 70 and 90 are groups' labels (contract 21.16). */
    private val withGroups = ProCapture(on = true, people = listOf(jan, priya), groupLabels = setOf(90L, 70L))

    @Test
    fun `a requester who is a person is who it was promised to and is no label of the task`() {
        val result = parse("\$Jan site list for \$Priya friday check on wednesday", withGroups)

        assertEquals("site list", result.title)
        val checkOn = result.checkOn
        assertTrue(checkOn != null, "check on is read")
        // The owner's label only: the requester is never added to the task.
        assertEquals(31L, withGroups.personLabelOf(result))
        // A named requester wins over the group the task carries.
        assertEquals(
            """{"dir":"theirs","person":31,"to":{"person":43},"check_on":"$checkOn"}""",
            withGroups.promiseOf(result, labelIds = setOf(90L)).toString(),
        )
        assertNull(withGroups.newRequesterOf(result))
    }

    @Test
    fun `a new requester is made a person in quick add and kept in words when that fails`() {
        val quickAdd = TaskParser.parse(
            "\$Jan budget for \$Lalith", withGroups.config(ParserConfig(), makesPeople = true),
            reference = now, zone = zone, people = withGroups.parserPeople,
        )
        assertEquals("budget", quickAdd.title)
        assertEquals("Lalith", withGroups.newRequesterOf(quickAdd))
        // Not made (no server): the name in words.
        assertEquals(
            """{"dir":"theirs","person":31,"to":{"text":"Lalith"}}""",
            withGroups.promiseOf(quickAdd, labelIds = setOf(70L)).toString(),
        )
        // Made: the new person.
        val made = quickAdd.copy(requester = quickAdd.requester!!.copy(id = 77, name = null))
        assertEquals("""{"dir":"theirs","person":31,"to":{"person":77}}""", withGroups.withPerson(Person(77, "Lalith")).promiseOf(made).toString())
        // Off, or without the promises layer, nobody is made.
        assertNull(ProCapture.OFF.newRequesterOf(quickAdd))
        assertNull(ProCapture(on = true, promises = false).newRequesterOf(quickAdd))
    }

    @Test
    fun `a promise of theirs on a group's action is made to the lowest group label on the task`() {
        val theirs = parse("Jan: send the plan", withGroups)
        assertEquals(
            """{"dir":"theirs","person":31,"to":{"group":70}}""",
            withGroups.promiseOf(theirs, labelIds = listOf(90L, 5L, 70L)).toString(),
        )
        // No group label: promised to you, no `to`.
        assertEquals("""{"dir":"theirs","person":31}""", withGroups.promiseOf(theirs, labelIds = listOf(5L)).toString())
        // Yours to them: the group does not make it the group's.
        val mine = parse("for Jan: review the deck", withGroups)
        assertEquals("""{"dir":"mine","person":31}""", withGroups.promiseOf(mine, labelIds = listOf(70L)).toString())
    }

    @Test
    fun `check on is written as a local day`() {
        val result = parse("Jan: the numbers friday check on thursday 9am", withGroups)
        assertEquals("the numbers", result.title)
        assertEquals("""{"dir":"theirs","person":31,"check_on":"${result.checkOn}"}""", withGroups.promiseOf(result).toString())
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(result.checkOn.toString()))
    }

    @Test
    fun `an estimate is read only while Pro is on`() {
        assertEquals(90, on.estimateOf(parse("Write the report ~1h30", on)))
        assertNull(on.estimateOf(parse("Write the report", on)))
        assertNull(ProCapture.OFF.estimateOf(parse("Write the report ~1h30", ProCapture.OFF)))
    }

    @Test
    fun `a dollar name that matches nobody is a new person only in quick add`() {
        val quickAdd = TaskParser.parse(
            "\$Lalith: call back", on.config(ParserConfig(), makesPeople = true),
            reference = now, zone = zone, people = on.parserPeople,
        )
        assertEquals("call back", quickAdd.title)
        assertEquals("Lalith", on.newPersonOf(quickAdd))
        assertNull(on.personLabelOf(quickAdd))
        assertNull(on.promiseOf(quickAdd))

        // Made: the person's label and promise, as for a known person.
        val made = on.withPerson(Person(77, "Lalith"))
        val saved = quickAdd.copy(person = quickAdd.person!!.copy(id = 77, name = null))
        assertEquals(77L, made.personLabelOf(saved))
        assertEquals("""{"dir":"theirs","person":77}""", made.promiseOf(saved).toString())

        // Every other path keeps the name in the title; a known person is read everywhere.
        val elsewhere = parse("\$Lalith: call back", on)
        assertEquals("\$Lalith: call back", elsewhere.title)
        assertNull(elsewhere.person)
        val known = parse("call \$Jan back", on)
        assertEquals("call back", known.title)
        assertEquals(31L, on.personLabelOf(known))
    }

    @Test
    fun `without the promises layer no person is read`() {
        val noPromises = ProCapture(on = true, promises = false)
        val result = TaskParser.parse(
            "\$Lalith printing !flag", noPromises.config(ParserConfig(), makesPeople = true),
            reference = now, zone = zone, people = noPromises.parserPeople,
        )
        assertEquals("\$Lalith printing", result.title)
        assertTrue(result.flag)
        assertNull(result.person)
        assertNull(noPromises.newPersonOf(result))
    }

    @Test
    fun `making a person marks the label with that name or creates one`() = runTest {
        val labels = FakeLabelRepository(listOf(Label(7, "lalith "), Label(5, "Lalith"), Label(9, "Other")))
        val prefs = ProPreferences(InMemorySyncedEntries())

        val existing = makePersonLabel("  Lalith ", labels, prefs)
        assertEquals(5L, (existing as NetworkResult.Success).data.id)
        assertTrue(labels.created.isEmpty())
        assertTrue(prefs.snapshot.first().isPerson(5))

        val created = makePersonLabel("Jan  de Vries", labels, prefs) as NetworkResult.Success
        assertEquals(listOf("Jan de Vries"), labels.created.map { it.title })
        assertTrue(prefs.snapshot.first().isPerson(created.data.id))

        // It needs the server: nothing is made or marked without it.
        labels.refreshResult = NetworkResult.Error("Can't reach the server", offline = true)
        assertTrue(makePersonLabel("Sam", labels, prefs) is NetworkResult.Error)
        assertEquals(1, labels.created.size)
    }
}
