package com.rendyhd.vicu.ui.screens.pro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.pro_group_blockers
import com.rendyhd.vicu.resources.pro_group_commitments
import com.rendyhd.vicu.resources.pro_group_decided_by
import com.rendyhd.vicu.resources.pro_group_decisions
import com.rendyhd.vicu.resources.pro_group_issues
import com.rendyhd.vicu.resources.pro_group_no_decisions
import com.rendyhd.vicu.resources.pro_group_no_issues
import com.rendyhd.vicu.resources.pro_group_no_owner
import com.rendyhd.vicu.resources.pro_group_no_questions
import com.rendyhd.vicu.resources.pro_group_no_session
import com.rendyhd.vicu.resources.pro_group_not_group
import com.rendyhd.vicu.resources.pro_group_part_done
import com.rendyhd.vicu.resources.pro_group_part_done_recent
import com.rendyhd.vicu.resources.pro_group_part_due
import com.rendyhd.vicu.resources.pro_group_part_due_no_session
import com.rendyhd.vicu.resources.pro_group_part_fresh
import com.rendyhd.vicu.resources.pro_group_part_open
import com.rendyhd.vicu.resources.pro_group_part_overdue
import com.rendyhd.vicu.resources.pro_group_part_yours
import com.rendyhd.vicu.resources.pro_group_plans
import com.rendyhd.vicu.resources.pro_group_questions
import com.rendyhd.vicu.resources.pro_group_read_only
import com.rendyhd.vicu.resources.pro_group_reliability
import com.rendyhd.vicu.resources.pro_group_reliability_more
import com.rendyhd.vicu.resources.pro_group_stops
import com.rendyhd.vicu.resources.pro_group_title
import com.rendyhd.vicu.resources.pro_group_you
import com.rendyhd.vicu.resources.pro_plan_closed
import com.rendyhd.vicu.resources.pro_plan_days
import com.rendyhd.vicu.ui.components.section.SectionHeader
import com.rendyhd.vicu.ui.components.section.SectionLevel
import com.rendyhd.vicu.ui.components.section.SectionTone
import com.rendyhd.vicu.ui.components.section.openCount
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.pro.Groups
import com.rendyhd.vicu.util.pro.OwnerActions
import com.rendyhd.vicu.util.pro.Reliability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** A part of a group's page, in the page's order (contract 21.16). */
enum class GroupPart { OVERDUE, DUE, FRESH, OPEN, YOURS, DONE }

/** An owner's actions in a part; [owner] is a person's label id, [Groups.YOU], or null for nobody. */
data class GroupOwnerUi(val owner: Long?, val name: String?, val tasks: List<Task>)

data class GroupPartUi(val part: GroupPart, val owners: List<GroupOwnerUi>) {
    val tasks: List<Task> get() = owners.flatMap { it.tasks }
}

/** One of the group's 90-day plans (contract 21.20): its project, the project's name and the plan's days. */
data class GroupPlanUi(val project: Long, val name: String, val start: LocalDate, val end: LocalDate, val closed: LocalDate?)

/** A decision of the group: what was decided, by whom (a name, or null) and when (a local day, or null). */
data class GroupDecisionUi(val id: String, val text: String, val by: String?, val day: LocalDate?)

data class GroupUiState(
    val on: Boolean = false,
    /** False when the label is not a group's (or is gone). */
    val isGroup: Boolean = false,
    val name: String? = null,
    /** True once the group had a session. */
    val hadSession: Boolean = false,
    val reliability: Reliability? = null,
    val parts: List<GroupPartUi> = emptyList(),
    val issues: List<String> = emptyList(),
    val decisions: List<GroupDecisionUi> = emptyList(),
    val questions: List<String> = emptyList(),
    /** Open blockers, commitments and stops, oldest first (contract 21.16). */
    val blockers: List<String> = emptyList(),
    val commitments: List<String> = emptyList(),
    val stops: List<String> = emptyList(),
    /** The group's plans, open ones first (contract 21.20). */
    val plans: List<GroupPlanUi> = emptyList(),
)

/**
 * A group's page, read-only on the phone (contract 21.16): the reliability line in counts, its
 * actions by part and owner (verdicts through each row's own verdict sheet), its open issues,
 * recent decisions, open questions, open blockers, commitments and stops, and its 90-day plans
 * (21.20). Setting a group up, sessions, ranking issues and making plans are desktop and web
 * features.
 */
class GroupViewModel(
    savedStateHandle: SavedStateHandle,
    worldSource: ProWorldSource,
    private val taskRepository: TaskRepository,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private val labelId: Long = savedStateHandle["labelId"]!!

    /** The group's done actions the device holds (done since the last session, or in the last 7 days). */
    private val doneActions: Flow<List<Task>> = worldSource.world
        .map { it.promisesOn }
        .distinctUntilChanged()
        .let { on ->
            combine(on, taskRepository.getAllTasksFlat()) { promises, all ->
                if (!promises) emptyList() else all.filter { task -> task.done && task.labels.any { it.id == labelId } }
            }
        }

    private fun build(world: ProWorld, done: List<Task>): GroupUiState {
        if (!world.promisesOn) return GroupUiState(on = false)
        val group = world.groupByLabel[labelId] ?: return GroupUiState(on = true, isGroup = false, name = world.label(labelId)?.title)
        val tasks = world.open.filter { task -> task.labels.any { it.id == labelId } } + done
        val byId = tasks.associateBy { it.id }
        val page = Groups.groupPage(group, tasks, world.ledger, world.today, world.zone)
        fun owners(list: List<OwnerActions>, scope: Long): List<GroupOwnerUi> = list.map { part ->
            val name = part.owner?.takeIf { it != Groups.YOU }?.let(world::nameOf)
            GroupOwnerUi(part.owner, name, completions.merge(part.tasks.mapNotNull { byId[it] }, listScope = scope * 1_000_000 + (part.owner ?: -1L)))
        }
        val parts = listOf(
            GroupPartUi(GroupPart.OVERDUE, owners(page.overdue, 1)),
            GroupPartUi(GroupPart.DUE, owners(page.due, 2)),
            GroupPartUi(GroupPart.FRESH, owners(page.fresh, 3)),
            GroupPartUi(GroupPart.OPEN, owners(page.open, 4)),
            GroupPartUi(
                GroupPart.YOURS,
                listOf(GroupOwnerUi(Groups.YOU, null, completions.merge(page.yours.mapNotNull { byId[it] }, listScope = 5_000_000L))),
            ),
            GroupPartUi(GroupPart.DONE, owners(page.done, 6)),
        ).filter { it.tasks.isNotEmpty() }
        val text = { value: kotlinx.serialization.json.JsonObject ->
            (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        }
        return GroupUiState(
            on = true,
            isGroup = true,
            name = world.groupName(labelId),
            hadSession = page.since != null,
            reliability = Groups.reliability(group, tasks, world.ledger, world.today, world.zone),
            parts = parts,
            issues = Groups.openIssues(world.ledger, labelId).map { text(it.value) }.filter { it.isNotEmpty() },
            decisions = Groups.groupLog(world.ledger, labelId, "decision").take(RECENT_DECISIONS).map { entry ->
                val by = (entry.value["by"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
                val day = (entry.value["at"] as? JsonPrimitive)?.content?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                GroupDecisionUi(entry.id, text(entry.value), by?.let(world::nameOf), day)
            },
            questions = Groups.groupLog(world.ledger, labelId, "unknown")
                .filter { entry -> (entry.value["resolved"] as? JsonPrimitive)?.content.let { it == null || it == "false" || it == "" || it == "null" } }
                .map { text(it.value) }
                .filter { it.isNotEmpty() },
            blockers = openLog(world, "blocker", text),
            commitments = openLog(world, "commitment", text),
            stops = openLog(world, "stop", text),
            plans = world.plans.filter { it.plan.group == labelId }.map { entry ->
                val name = world.projects.firstOrNull { it.id == entry.project }?.title.orEmpty()
                GroupPlanUi(entry.project, name, entry.plan.start, entry.plan.end, entry.plan.closed)
            },
        )
    }

    private fun openLog(world: ProWorld, kind: String, text: (kotlinx.serialization.json.JsonObject) -> String): List<String> =
        Groups.openLog(world.ledger, labelId, kind).map { text(it.value) }.filter { it.isNotEmpty() }

    val uiState: StateFlow<GroupUiState> = combine(worldSource.world, doneActions, completions.state) { world, done, _ -> build(world, done) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value, emptyList()))

    private companion object {
        const val RECENT_DECISIONS = 8
    }
}

/** A group's page (contract 21.16), read-only: what is open, by part and owner, and what the group decided. */
@Composable
fun GroupScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlan: (Long) -> Unit = {},
    viewModel: GroupViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val held by viewModel.completedIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val title = state.name ?: stringResource(Res.string.pro_group_title)
    val fmt = LocalDateFormat.current
    val today = LocalClockDay.current.date
    ProScreenScaffold(title, onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        when {
            !state.on -> item(key = "off") { ProOffNoticeText(onOpenSettings) }
            !state.isGroup -> item(key = "not_group") { Muted(stringResource(Res.string.pro_group_not_group), Modifier.padding(24.dp)) }
            else -> {
                item(key = "reliability") {
                    val rel = state.reliability
                    val line = if (rel == null) {
                        stringResource(Res.string.pro_group_no_session)
                    } else {
                        val main = pluralStringResource(
                            Res.plurals.pro_group_reliability,
                            rel.sessions,
                            rel.sessions,
                            rel.onTime,
                            rel.total,
                            rel.late,
                            rel.openPastDue,
                        )
                        if (rel.dropped > 0 || rel.takenOver > 0) {
                            main + " " + stringResource(Res.string.pro_group_reliability_more, rel.dropped, rel.takenOver)
                        } else {
                            main
                        }
                    }
                    Muted(line, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                state.parts.forEach { part ->
                    item(key = "part_${part.part}", contentType = "header") {
                        SectionHeader(
                            title = partTitle(part.part, state.hadSession),
                            count = openCount(part.tasks, held),
                            tone = if (part.part == GroupPart.OVERDUE) SectionTone.OVERDUE else SectionTone.DEFAULT,
                        )
                    }
                    part.owners.forEach { owner ->
                        if (part.part != GroupPart.YOURS) {
                            item(key = "owner_${part.part}_${owner.owner ?: -1L}", contentType = "owner") {
                                SectionHeader(
                                    title = when (owner.owner) {
                                        null -> stringResource(Res.string.pro_group_no_owner)
                                        Groups.YOU -> stringResource(Res.string.pro_group_you)
                                        else -> owner.name.orEmpty()
                                    },
                                    level = SectionLevel.TWO,
                                )
                            }
                        }
                        items(owner.tasks, key = { "${part.part}_${owner.owner ?: -1L}_${it.id}" }, contentType = { "task" }) { task ->
                            ProTaskRow(task, held, viewModel, onTaskClick, Modifier.animateItem())
                        }
                    }
                }
                logList("issues", Res.string.pro_group_issues, Res.string.pro_group_no_issues, state.issues)
                item(key = "decisions_header", contentType = "header") {
                    SectionHeader(title = stringResource(Res.string.pro_group_decisions), count = state.decisions.size)
                }
                if (state.decisions.isEmpty()) {
                    item(key = "decisions_none") { Muted(stringResource(Res.string.pro_group_no_decisions), LOG_PADDING) }
                }
                items(state.decisions, key = { "decision_${it.id}" }) { decision ->
                    Column(LOG_PADDING, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(decision.text, style = MaterialTheme.typography.bodyMedium)
                        val meta = listOfNotNull(decision.by, decision.day?.let { DateDisplay.formatWeekdayDate(it, today, fmt) })
                        if (meta.size == 2) {
                            Muted(stringResource(Res.string.pro_group_decided_by, meta[0], meta[1]))
                        } else if (meta.size == 1) {
                            Muted(meta[0])
                        }
                    }
                }
                logList("questions", Res.string.pro_group_questions, Res.string.pro_group_no_questions, state.questions)
                // Blockers, commitments and stops (contract 21.16): shown when the group has any.
                logList("blockers", Res.string.pro_group_blockers, null, state.blockers)
                logList("commitments", Res.string.pro_group_commitments, null, state.commitments)
                logList("stops", Res.string.pro_group_stops, null, state.stops)
                if (state.plans.isNotEmpty()) {
                    item(key = "plans_header", contentType = "header") {
                        SectionHeader(title = stringResource(Res.string.pro_group_plans), count = state.plans.size)
                    }
                    items(state.plans, key = { "plan_${it.project}" }) { plan ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onOpenPlan(plan.project) }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(plan.name, style = MaterialTheme.typography.bodyMedium)
                            Muted(
                                stringResource(
                                    Res.string.pro_plan_days,
                                    DateDisplay.formatWeekdayDate(plan.start, today, fmt),
                                    DateDisplay.formatWeekdayDate(plan.end, today, fmt),
                                ),
                            )
                            plan.closed?.let { closed ->
                                Muted(stringResource(Res.string.pro_plan_closed, DateDisplay.formatWeekdayDate(closed, today, fmt)))
                            }
                        }
                    }
                }
                item(key = "read_only") {
                    Muted(stringResource(Res.string.pro_group_read_only), Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                }
            }
        }
    }
}

private val LOG_PADDING = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)

/** A plain read-only list of the group's log under a header: issues, questions, blockers, commitments or stops. */
private fun LazyListScope.logList(
    key: String,
    title: org.jetbrains.compose.resources.StringResource,
    empty: org.jetbrains.compose.resources.StringResource?,
    lines: List<String>,
) {
    // Without an empty text the list is left out while it has nothing.
    if (lines.isEmpty() && empty == null) return
    item(key = "${key}_header", contentType = "header") { SectionHeader(title = stringResource(title), count = lines.size) }
    if (lines.isEmpty() && empty != null) {
        item(key = "${key}_none") { Muted(stringResource(empty), LOG_PADDING) }
    }
    lines.forEachIndexed { index, line ->
        item(key = "${key}_$index") { Text(line, style = MaterialTheme.typography.bodyMedium, modifier = LOG_PADDING) }
    }
}

@Composable
private fun partTitle(part: GroupPart, hadSession: Boolean): String = stringResource(
    when (part) {
        GroupPart.OVERDUE -> Res.string.pro_group_part_overdue
        GroupPart.DUE -> if (hadSession) Res.string.pro_group_part_due else Res.string.pro_group_part_due_no_session
        GroupPart.FRESH -> Res.string.pro_group_part_fresh
        GroupPart.OPEN -> Res.string.pro_group_part_open
        GroupPart.YOURS -> Res.string.pro_group_part_yours
        GroupPart.DONE -> if (hadSession) Res.string.pro_group_part_done else Res.string.pro_group_part_done_recent
    },
)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
