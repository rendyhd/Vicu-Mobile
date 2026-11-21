package com.rendyhd.vicu.ui.screens.pro

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.data.repository.ProWorld
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.pro_group_you
import com.rendyhd.vicu.resources.pro_plan_cell_delivered
import com.rendyhd.vicu.resources.pro_plan_cell_description
import com.rendyhd.vicu.resources.pro_plan_cell_dropped
import com.rendyhd.vicu.resources.pro_plan_cell_due
import com.rendyhd.vicu.resources.pro_plan_cell_empty
import com.rendyhd.vicu.resources.pro_plan_cell_late
import com.rendyhd.vicu.resources.pro_plan_cell_slipped
import com.rendyhd.vicu.resources.pro_plan_cell_this_week
import com.rendyhd.vicu.resources.pro_plan_closed
import com.rendyhd.vicu.resources.pro_plan_days
import com.rendyhd.vicu.resources.pro_plan_delivered
import com.rendyhd.vicu.resources.pro_plan_item_progress
import com.rendyhd.vicu.resources.pro_plan_next
import com.rendyhd.vicu.resources.pro_plan_next_no_date
import com.rendyhd.vicu.resources.pro_plan_no_items
import com.rendyhd.vicu.resources.pro_plan_none
import com.rendyhd.vicu.resources.pro_plan_overdue
import com.rendyhd.vicu.resources.pro_plan_read_only
import com.rendyhd.vicu.resources.pro_plan_title
import com.rendyhd.vicu.resources.pro_plan_week
import com.rendyhd.vicu.resources.pro_plan_week_short
import com.rendyhd.vicu.ui.components.shared.LocalClockDay
import com.rendyhd.vicu.ui.components.shared.LocalDateFormat
import com.rendyhd.vicu.ui.navigation.NavigationTicker
import com.rendyhd.vicu.ui.screens.shared.CompletionToast
import com.rendyhd.vicu.ui.screens.shared.NoCompletionToast
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.pro.Groups
import com.rendyhd.vicu.util.pro.PlanCell
import com.rendyhd.vicu.util.pro.ProjectPlan
import com.rendyhd.vicu.util.pro.ProjectPlans
import com.rendyhd.vicu.util.pro.Promises
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** One item of a plan: its task, its owner, its counts, its next open milestone and its weeks. */
data class PlanItemUi(
    val task: Task,
    /** A person's label id, [Groups.YOU], or null for nobody. */
    val owner: Long?,
    /** The person's name; null for you or nobody. */
    val ownerName: String?,
    val delivered: Int,
    val total: Int,
    val next: Task?,
    val nextDue: LocalDate?,
    val weeks: List<PlanCell>,
)

data class ProjectPlanUiState(
    val on: Boolean = false,
    val name: String? = null,
    /** Null when the project is not a plan. */
    val plan: ProjectPlan? = null,
    /** The week today falls in (0 for the first), or null outside the plan. */
    val week: Int? = null,
    val weekCount: Int = 0,
    val delivered: Int = 0,
    val total: Int = 0,
    val overdue: Int = 0,
    val items: List<PlanItemUi> = emptyList(),
)

/**
 * A 90-day plan, read-only on the phone (contract 21.20): the project's items and milestones
 * counted from its tasks and the verdicts on them, week by week. Making, closing and carrying over
 * a plan are desktop and web features.
 */
class ProjectPlanViewModel(
    savedStateHandle: SavedStateHandle,
    worldSource: ProWorldSource,
    taskRepository: TaskRepository,
    navigationTicker: NavigationTicker = NavigationTicker(),
    completionToast: CompletionToast = NoCompletionToast,
) : ProTaskListViewModel(worldSource, taskRepository, navigationTicker, completionToast) {

    private val projectId: Long = savedStateHandle["projectId"]!!

    /**
     * The project's tasks the device holds, open and done, every subtask a row of its own, in the
     * project's position order. Done tasks reach the phone in pages, so an old one may be missing.
     */
    private val projectTasks: Flow<List<Task>> = worldSource.world
        .map { it.promisesOn }
        .distinctUntilChanged()
        .let { on ->
            combine(on, taskRepository.getAllTasksFlat()) { promises, all ->
                if (!promises) {
                    emptyList()
                } else {
                    all.filter { it.projectId == projectId }.sortedWith(compareBy<Task> { it.position }.thenBy { it.id })
                }
            }
        }

    private fun build(world: ProWorld, tasks: List<Task>): ProjectPlanUiState {
        if (!world.promisesOn) return ProjectPlanUiState(on = false)
        val name = world.projects.firstOrNull { it.id == projectId }?.title
        val plan = world.planOf(projectId) ?: return ProjectPlanUiState(on = true, name = name)
        val view = ProjectPlans.planView(plan, tasks, world.ledger, world.today, world.zone)
        val byId = tasks.associateBy { it.id }
        val items = view.items.mapNotNull { item ->
            val task = byId[item.id] ?: return@mapNotNull null
            val next = item.next?.let { byId[it] }
            PlanItemUi(
                task = task,
                owner = item.owner,
                ownerName = item.owner?.takeIf { it != Groups.YOU }?.let(world::nameOf),
                delivered = item.delivered,
                total = item.total,
                next = next,
                nextDue = next?.let { Promises.effectiveDue(it, Promises.promiseOf(world.ledger, it.id), world.zone) },
                weeks = item.weeks,
            )
        }
        return ProjectPlanUiState(
            on = true,
            name = name,
            plan = plan,
            week = view.week,
            weekCount = view.weeks.size,
            delivered = view.delivered,
            total = view.total,
            overdue = view.overdue,
            items = items,
        )
    }

    val uiState: StateFlow<ProjectPlanUiState> = combine(worldSource.world, projectTasks) { world, tasks -> build(world, tasks) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(worldSource.world.value, emptyList()))
}

/** A 90-day plan (contract 21.20), read-only: the days, this week, the totals and one card per item. */
@Composable
fun ProjectPlanScreen(
    onTaskClick: (Long) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProjectPlanViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val title = state.name ?: stringResource(Res.string.pro_plan_title)
    val fmt = LocalDateFormat.current
    val today = LocalClockDay.current.date
    val plan = state.plan
    ProScreenScaffold(title, onOpenDrawer, onNavigateToSearch, error, viewModel::clearError) {
        when {
            !state.on -> item(key = "off") { ProOffNoticeText(onOpenSettings) }
            plan == null -> item(key = "not_plan") { PlanMuted(stringResource(Res.string.pro_plan_none), Modifier.padding(24.dp)) }
            else -> {
                item(key = "summary") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            stringResource(
                                Res.string.pro_plan_days,
                                DateDisplay.formatWeekdayDate(plan.start, today, fmt),
                                DateDisplay.formatWeekdayDate(plan.end, today, fmt),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.week?.let { week ->
                            PlanMuted(stringResource(Res.string.pro_plan_week, week + 1, state.weekCount))
                        }
                        plan.closed?.let { closed ->
                            PlanMuted(stringResource(Res.string.pro_plan_closed, DateDisplay.formatWeekdayDate(closed, today, fmt)))
                        }
                        PlanMuted(stringResource(Res.string.pro_plan_delivered, state.delivered, state.total))
                        if (state.overdue > 0) PlanMuted(stringResource(Res.string.pro_plan_overdue, state.overdue))
                    }
                }
                if (state.items.isEmpty()) {
                    item(key = "no_items") { PlanMuted(stringResource(Res.string.pro_plan_no_items), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                }
                items(state.items, key = { "item_${it.task.id}" }) { item ->
                    PlanItemCard(item, state.week, onTaskClick)
                }
                item(key = "read_only") {
                    PlanMuted(stringResource(Res.string.pro_plan_read_only), Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun PlanItemCard(item: PlanItemUi, thisWeek: Int?, onTaskClick: (Long) -> Unit) {
    val fmt = LocalDateFormat.current
    val today = LocalClockDay.current.date
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onTaskClick(item.task.id) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val owner = when (item.owner) {
                    null -> null
                    Groups.YOU -> stringResource(Res.string.pro_group_you)
                    else -> item.ownerName
                }
                val progress = stringResource(Res.string.pro_plan_item_progress, item.delivered, item.total)
                PlanMuted(listOfNotNull(owner, progress).joinToString(", "))
            }
            item.next?.let { next ->
                val line = item.nextDue?.let { due ->
                    stringResource(Res.string.pro_plan_next, next.title, DateDisplay.formatWeekdayDate(due, today, fmt))
                } ?: stringResource(Res.string.pro_plan_next_no_date, next.title)
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onTaskClick(next.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.weeks.forEachIndexed { index, cell -> PlanWeekCell(index, cell, index == thisWeek) }
            }
        }
    }
}

/**
 * One week of an item: its number and the counts of what happened in it, in words. This week has
 * a heavier border; nothing is told by colour alone, and no count is a judgement.
 */
@Composable
private fun PlanWeekCell(index: Int, cell: PlanCell, isThisWeek: Boolean) {
    val counts = buildList {
        if (cell.due > 0) add(stringResource(Res.string.pro_plan_cell_due, cell.due))
        if (cell.delivered > 0) add(stringResource(Res.string.pro_plan_cell_delivered, cell.delivered))
        if (cell.late > 0) add(stringResource(Res.string.pro_plan_cell_late, cell.late))
        if (cell.slipped > 0) add(stringResource(Res.string.pro_plan_cell_slipped, cell.slipped))
        if (cell.dropped > 0) add(stringResource(Res.string.pro_plan_cell_dropped, cell.dropped))
    }
    val what = (if (counts.isEmpty()) listOf(stringResource(Res.string.pro_plan_cell_empty)) else counts) +
        listOfNotNull(if (isThisWeek) stringResource(Res.string.pro_plan_cell_this_week) else null)
    val description = stringResource(Res.string.pro_plan_cell_description, index + 1, what.joinToString(", "))
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(76.dp)
            .border(if (isThisWeek) 2.dp else 1.dp, if (isThisWeek) scheme.onSurface else scheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            stringResource(Res.string.pro_plan_week_short, index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        counts.forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

@Composable
private fun PlanMuted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
