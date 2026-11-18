package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.util.DateUtils
import com.rendyhd.vicu.util.NetworkResult
import com.rendyhd.vicu.util.parser.ParseResult
import com.rendyhd.vicu.util.parser.ParserConfig
import com.rendyhd.vicu.util.parser.TokenType
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person
import com.rendyhd.vicu.util.pro.ProLayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What quick add takes from Pro (docs/cross-app-semantics-v1.md sections 5.5 and 21): whether the
 * Pro phrases are read, and the people `Jan:` and `$Jan` may name. [OFF] is today's Vicu: the parser
 * reads the text as before and nothing below writes a field. Without the promises layer
 * ([promises] false) no person is read at all, so `$Lalith` stays in the title.
 */
data class ProCapture(
    val on: Boolean = false,
    val people: List<Person> = emptyList(),
    val promises: Boolean = true,
) {

    /**
     * [base] with Pro's phrases on while Pro is on; unchanged otherwise. [makesPeople] is for quick
     * add, the one path that makes a person from a `$` name that matches nobody (section 21.5); on
     * every other path such a name stays in the title.
     */
    fun config(base: ParserConfig, makesPeople: Boolean = false): ParserConfig {
        if (!on) return base
        var config = base
        if (!config.pro) config = config.copy(pro = true)
        if (!makesPeople && config.newPeople) config = config.copy(newPeople = false)
        if (!promises && TokenType.PERSON !in config.suppressTypes) config = config.copy(suppressTypes = config.suppressTypes + TokenType.PERSON)
        return config
    }

    /**
     * A new person a `$` name asks for (section 21.5): the name, while Pro and its promises are on.
     * The person is made when the task is saved ([ProCaptureSource.makePerson]).
     */
    fun newPersonOf(result: ParseResult?): String? {
        if (!on || !promises) return null
        val person = result?.person ?: return null
        return person.name?.takeIf { person.id == null && it.isNotBlank() }
    }

    /** This capture with [person] among its people, once quick add has made them. */
    fun withPerson(person: Person): ProCapture =
        if (people.any { it.id == person.id }) this else copy(people = people + person)

    /** The people the parser may match; none while Pro is off. */
    val parserPeople: List<Person> get() = if (on) people else emptyList()

    /**
     * The lone start date `starts monday` gives, as stored (section 21.3): that local midnight, or
     * the moment named. Null while Pro is off, without the phrase, or when a block holds the start.
     */
    fun startDateOf(result: ParseResult?, zone: TimeZone): String? {
        if (!on || result == null || result.block != null) return null
        return result.start?.toInstant(zone)?.toString()
    }

    /** `!flag` read while Pro is on: the task is flagged (`is_favorite`, section 21.4). */
    fun flagOf(result: ParseResult?): Boolean = on && result?.flag == true

    /** The person label `Jan:` named (section 21.5), while Pro is on and the label is still a person. */
    fun personLabelOf(result: ParseResult?): Long? {
        if (!on) return null
        val id = result?.person?.id ?: return null
        return id.takeIf { wanted -> people.any { it.id == wanted } }
    }

    /**
     * The promise `Jan:` (or `for Jan:`) makes of the new task (section 21.6), with what the entry
     * sheet's fields add: their words and how you will see it is done. Null while Pro is off,
     * without a person, or when the label is no longer a person.
     */
    fun promiseOf(result: ParseResult?, words: String? = null, doneWhen: String? = null): JsonObject? {
        val person = personLabelOf(result) ?: return null
        val mine = result?.person?.mine == true
        val fields = LinkedHashMap<String, JsonElement>()
        fields["dir"] = JsonPrimitive(if (mine) "mine" else "theirs")
        fields["person"] = JsonPrimitive(person)
        words?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["words"] = JsonPrimitive(it) }
        doneWhen?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["done_when"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    /** `~45m` read while Pro is on: the estimate in minutes (section 21.2, kept in the ledger). */
    fun estimateOf(result: ParseResult?): Int? = if (on) result?.estimate?.takeIf { it in 1..1440 } else null

    /**
     * A new task with what the text said through Pro's phrases: the start date unless the task has
     * a block (a block replaces a start date, decision 17), and the flag. The person's label is added
     * by the caller with the other labels ([personLabelOf]).
     */
    fun applyToNew(task: Task, result: ParseResult?, zone: TimeZone): Task {
        if (!on || result == null) return task
        var next = task
        val start = startDateOf(result, zone)
        // A block (chosen with a chip or by `for 30m`) already holds the start.
        if (start != null && DateUtils.parseIsoDate(task.startDate.ifBlank { null }) == null) {
            next = next.copy(startDate = start)
        }
        if (flagOf(result)) next = next.copy(isFavorite = true)
        return next
    }

    companion object {
        val OFF = ProCapture()
    }
}

/**
 * [ProCapture] as the device's settings make it: on while Pro is on here, with the labels marked as
 * a person (and their aliases from the ledger) while the promises layer is on. Off, it reads no
 * label, preference or ledger at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProCaptureSource(
    behaviorPrefs: BehaviorPrefsStore,
    private val proPreferences: ProPreferences,
    private val labelRepository: LabelRepository,
    private val proLedger: ProLedgerRepository,
) {
    private data class Switches(val on: Boolean, val people: Boolean)

    val capture: Flow<ProCapture> = behaviorPrefs.getPrefs()
        .map { Switches(it.isProEnabled(), it.isProEnabled(ProLayer.PROMISES)) }
        .distinctUntilChanged()
        .flatMapLatest { switches ->
            when {
                !switches.on -> flowOf(ProCapture.OFF)
                !switches.people -> flowOf(ProCapture(on = true, promises = false))
                else -> combine(proPreferences.snapshot, labelRepository.getAll(), proLedger.ledger) { prefs, labels, ledger ->
                    ProCapture(
                        on = true,
                        people = People.peopleWithAliases(labels.map { it.id to it.title }, prefs::isPerson, ledger),
                    )
                }
            }
        }
        .distinctUntilChanged()

    /** Makes [name] a person when quick add saves a task naming them with `$` ([makePersonLabel]). */
    suspend fun makePerson(name: String): NetworkResult<Person> = makePersonLabel(name, labelRepository, proPreferences)
}

private const val MAX_PERSON_NAME = 250

/**
 * Makes [name] a person (section 21.5, `$Lalith` in quick add): the label whose title is the name
 * (spacing and case ignored, the lowest id) becomes the person, else a label with that title is
 * created; then `label_kind.<id>` is set to "person". It needs the server: the labels are read from
 * it first, and a failure is answered as it came (the caller keeps the `$` phrase in the title).
 * The desktop's `ensurePerson` (`src/main/pro-people.ts`).
 */
internal suspend fun makePersonLabel(
    name: String,
    labelRepository: LabelRepository,
    proPreferences: ProPreferences,
): NetworkResult<Person> {
    val title = People.newPersonTitle(name)
    if (title.isEmpty() || title.length > MAX_PERSON_NAME) return NetworkResult.Error("not a name Vicu can use")
    val refreshed = labelRepository.refreshAll()
    if (refreshed is NetworkResult.Error) return refreshed
    val labels = labelRepository.getAll().first().filter { it.id > 0 }
    val existing = People.labelForNewPerson(labels.map { it.id to it.title }, title)
    val person = if (existing != null) {
        Person(existing, labels.first { it.id == existing }.title)
    } else {
        when (val made = labelRepository.create(Label(id = 0L, title = title, hexColor = ""))) {
            is NetworkResult.Success -> {
                // A label kept on the device for later (no connection) has no id to mark yet.
                if (made.data.id <= 0) return NetworkResult.Error("Can't reach the server", offline = true)
                Person(made.data.id, made.data.title)
            }
            is NetworkResult.Error -> return made
            NetworkResult.Loading -> return NetworkResult.Error("Can't reach the server", offline = true)
        }
    }
    proPreferences.setPerson(person.id, true)
    return NetworkResult.Success(person)
}
