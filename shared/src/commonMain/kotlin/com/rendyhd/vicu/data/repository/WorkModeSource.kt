package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.auth.AuthManager
import com.rendyhd.vicu.data.local.BehaviorPrefs
import com.rendyhd.vicu.data.local.BehaviorPrefsStore
import com.rendyhd.vicu.domain.model.Label
import com.rendyhd.vicu.domain.model.Project
import com.rendyhd.vicu.i18n.UiText
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.work_added_to_personal
import com.rendyhd.vicu.resources.work_hidden_show
import com.rendyhd.vicu.util.AppMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.rendyhd.vicu.domain.model.SyncedPrefKeys
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.LabelRepository
import com.rendyhd.vicu.domain.repository.PlatformRepositoryHooks
import com.rendyhd.vicu.domain.repository.ProjectRepository
import com.rendyhd.vicu.util.WorkMode
import com.rendyhd.vicu.util.WorkModeClause
import com.rendyhd.vicu.util.WorkProject
import com.rendyhd.vicu.util.WorkScope
import com.rendyhd.vicu.util.WorkScopeRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a list reads to apply the mode (docs/cross-app-semantics-v1.md section 22.4): the mode in
 * effect and what work is. [OFF] is every device with the feature off, and the mode All: nothing
 * is filtered, and nothing is read to find out.
 */
data class WorkState(val mode: WorkMode = WorkMode.ALL, val scope: WorkScope = WorkScope.NONE) {
    /** The mode hides something: Work or Personal. */
    val filtering: Boolean get() = mode != WorkMode.ALL

    fun passes(task: Task): Boolean =
        WorkScopeRules.passesWorkMode(task.projectId, task.labels.map { it.id }, scope, mode)

    /** The tasks of a flat list the mode shows, in the list's order. */
    fun filter(tasks: List<Task>): List<Task> = if (!filtering) tasks else tasks.filter(::passes)

    /**
     * The tasks of a list that nests subtasks under [parentOf] (null for a row of its own): a row
     * under a shown row is shown with it (22.4).
     */
    fun filter(tasks: List<Task>, parentOf: (Task) -> Long?): List<Task> =
        if (!filtering) tasks else WorkScopeRules.shownInWorkMode(tasks, scope, mode, { it.id }, { it.projectId }, { t -> t.labels.map { it.id } }, parentOf)

    /**
     * A screen opened on purpose (a project or a label): the tasks the mode shows, or all of them
     * while [showHidden] is on, and how many open tasks the mode leaves out (22.4).
     */
    fun split(tasks: List<Task>, showHidden: Boolean, parentOf: ((Task) -> Long?)? = null): WorkSplit {
        if (!filtering) return WorkSplit(tasks, 0)
        val shown = if (parentOf != null) filter(tasks, parentOf) else filter(tasks)
        val hidden = tasks.count { !it.done } - shown.count { !it.done }
        return WorkSplit(if (showHidden) tasks else shown, hidden)
    }

    fun isWork(task: Task): Boolean = WorkScopeRules.isWorkTask(task.projectId, task.labels.map { it.id }, scope)

    /** The server clause for a paged listing, or nothing to narrow it. */
    val clause: WorkModeClause get() = WorkScopeRules.workModeClause(scope, mode)

    /** The other mode, which a hidden task belongs to ("N more in Personal"); null in All. */
    val otherMode: WorkMode? get() = when (mode) {
        WorkMode.WORK -> WorkMode.PERSONAL
        WorkMode.PERSONAL -> WorkMode.WORK
        WorkMode.ALL -> null
    }

    companion object {
        val OFF = WorkState()
    }
}

/** A list split by the mode: what to show and how many open tasks were left out. */
class WorkSplit(val tasks: List<Task>, val hidden: Int)

/** The device's switches for Work and Personal, read for the settings and the drawer. */
data class WorkSettings(
    val enabled: Boolean = false,
    /** The mode last chosen (kept while the feature is off). */
    val chosen: WorkMode = WorkMode.ALL,
    val proOnlyInWork: Boolean = false,
) {
    val mode: WorkMode get() = if (enabled) chosen else WorkMode.ALL

    companion object {
        fun of(prefs: BehaviorPrefs) = WorkSettings(
            enabled = prefs.workModeEnabled,
            chosen = WorkScopeRules.parseWorkMode(prefs.workMode),
            proOnlyInWork = prefs.proOnlyInWork,
        )
    }
}

/**
 * Work and Personal (contract section 22) as the app reads and writes it: the device's mode
 * (behaviour preferences), what work is (the synced `work.label` and `work.project.<id>`) and the
 * rule applied to a list. Choosing the mode writes only that device setting; marking writes only the
 * synced entries. Off, [state] is [WorkState.OFF] without reading a project or a label.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkModeSource(
    private val synced: SyncedEntries,
    private val behaviorPrefs: BehaviorPrefsStore,
    private val projectRepository: ProjectRepository,
    private val labelRepository: LabelRepository,
    private val authManager: AuthManager,
    /** Redraws the widgets and the app icon count, which follow the mode; none in tests. */
    private val hooks: PlatformRepositoryHooks? = null,
    /** Says that a task made in Work mode stays Personal (contract 22.6); none in tests. */
    private val messages: AppMessages? = null,
) {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The device's switches. */
    val settings: Flow<WorkSettings> = behaviorPrefs.getPrefs().map { WorkSettings.of(it) }.distinctUntilChanged()

    /** The mode in effect on this device. */
    val mode: Flow<WorkMode> = settings.map { it.mode }.distinctUntilChanged()

    /** Whether the feature is on here. */
    val enabled: Flow<Boolean> = settings.map { it.enabled }.distinctUntilChanged()

    /** The two kinds of synced entry the scope reads, as one map. */
    private val workEntries: Flow<Map<String, JsonElement?>> =
        synced.entries.map { all -> all.filterKeys(WorkScopeRules::isWorkKey) }.distinctUntilChanged()

    /**
     * What work is, whatever the mode: the marking screens read it with the feature on. A label that
     * no longer exists counts as none (22.1).
     */
    val scope: Flow<WorkScope> = combine(
        workEntries,
        projectRepository.getAllIncludingArchived(),
        labelRepository.getAll(),
        authManager.inboxProjectId,
    ) { entries, projects, labels, inbox ->
        WorkScopeRules.scopeOf(entries, projects.map(::workProject), inbox, labels.mapTo(HashSet()) { it.id })
    }.distinctUntilChanged()

    /** The id the entry names as the work label, whether or not that label still exists. */
    val storedLabel: Flow<Long?> = workEntries.map { WorkScopeRules.readWorkLabel(it[SyncedPrefKeys.WORK_LABEL]) }.distinctUntilChanged()

    /** What a list applies; [WorkState.OFF] (and nothing read) in All. */
    val state: Flow<WorkState> = mode.flatMapLatest { mode ->
        if (mode == WorkMode.ALL) flowOf(WorkState.OFF) else scope.map { WorkState(mode, it) }
    }.distinctUntilChanged()

    suspend fun currentState(): WorkState = state.first()

    suspend fun currentScope(): WorkScope = scope.first()

    // --- Device settings ---

    suspend fun setEnabled(enabled: Boolean) {
        behaviorPrefs.setWorkModeEnabled(enabled)
        hooks?.updateWidgets()
    }

    suspend fun setMode(mode: WorkMode) {
        behaviorPrefs.setWorkMode(mode)
        hooks?.updateWidgets()
    }

    suspend fun setProOnlyInWork(on: Boolean) = behaviorPrefs.setProOnlyInWork(on)

    // --- Synced entries ---

    /** Sets the work label; null is none. False (nothing written) for an id that is not a label id. */
    suspend fun setWorkLabel(labelId: Long?): Boolean {
        val value = labelId?.let { JsonPrimitive(it) }
        if (!WorkScopeRules.isWritableWorkValue(SyncedPrefKeys.WORK_LABEL, value)) return false
        synced.set(SyncedPrefKeys.WORK_LABEL, value)
        return true
    }

    /**
     * Marks a project as work or not (22.7): writes only its own entry, none when the project would
     * be what is wanted without one. Not for the Inbox. [projects] is the list the parent comes from.
     */
    suspend fun setProjectWork(projectId: Long, want: Boolean, projects: List<Project>): Boolean {
        val inbox = authManager.getInboxProjectId()
        if (projectId == inbox) return false
        val parent = projects.firstOrNull { it.id == projectId }?.parentProjectId ?: 0L
        val parentIsWork = parent > 0 && parent in scope.first().projects
        val entry = WorkScopeRules.workEntryToWrite(want, parentIsWork)
        synced.set(SyncedPrefKeys.workProjectOf(projectId), entry?.let { JsonPrimitive(it) })
        return true
    }

    // --- New tasks (contract 22.6) ---

    /**
     * The id of the work label a task made now should also carry: in Work mode, for a task that would
     * not be work without it. Null otherwise, also in All and with the feature off (nothing is read
     * then). When a task made in Work mode stays Personal for want of a work label, this says so at
     * once, with a way to see it. Call it once per task a person makes, never for a duplicate or a
     * carrier.
     */
    suspend fun labelIdForNewTask(projectId: Long, labelIds: Collection<Long>, announce: Boolean = true): Long? {
        val work = currentState()
        if (!work.filtering) return null
        if (WorkScopeRules.newTaskStaysPersonal(projectId, labelIds, work.scope, work.mode)) {
            if (announce) messages?.post(UiText.Res(Res.string.work_added_to_personal), UiText.Res(Res.string.work_hidden_show)) {
                actionScope.launch { setMode(WorkMode.PERSONAL) }
            }
            return null
        }
        return WorkScopeRules.workLabelForNewTask(projectId, labelIds, work.scope, work.mode)
    }

    /** [task] with the work label on it when [labelIdForNewTask] says so (it travels with the create like any label). */
    suspend fun forNewTask(task: Task, announce: Boolean = true): Task {
        val id = labelIdForNewTask(task.projectId, task.labels.map { it.id }, announce) ?: return task
        val label = labelRepository.getById(id) ?: Label(id = id, title = "")
        return task.copy(labels = task.labels + label)
    }

    /** A project is gone: its entry is not used any more and goes (22.1). */
    suspend fun forgetProject(projectId: Long) {
        if (synced.entries.first()[SyncedPrefKeys.workProjectOf(projectId)] != null) synced.set(SyncedPrefKeys.workProjectOf(projectId), null)
    }

    /** A label is gone: when it was the work label, there is none (22.1). */
    suspend fun forgetLabel(labelId: Long) {
        if (WorkScopeRules.readWorkLabel(synced.entries.first()[SyncedPrefKeys.WORK_LABEL]) == labelId) synced.set(SyncedPrefKeys.WORK_LABEL, null)
    }

    private fun workProject(project: Project) = WorkProject(project.id, project.parentProjectId.takeIf { it > 0 })
}

/** The mode a time block's task belongs to when it is not the one on screen (drawn muted, 22.4); null when it is shown or the mode is All. */
fun WorkState.otherModeOf(task: Task): WorkMode? = if (filtering && !passes(task)) otherMode else null

/** The row a task is nested under in a list that nests subtasks (its `parenttask` relation), or null. */
fun parentTaskOf(task: Task): Long? = task.relatedTasks["parenttask"]?.firstOrNull()?.id?.takeIf { it > 0 }

/** The state a list applies: [WorkState.OFF] when a screen has no source (tests, the feature off). */
fun WorkModeSource?.stateOrOff(): Flow<WorkState> = this?.state ?: flowOf(WorkState.OFF)

/**
 * This list with the mode applied (contract 22.4). Call it where the repository's list comes in,
 * before the list is counted or cut; [parentOf] names the row a task is nested under when the list
 * nests subtasks. Without a source, or in All, the list is passed on as it is.
 */
fun Flow<List<Task>>.inWorkMode(source: WorkModeSource?, parentOf: ((Task) -> Long?)? = null): Flow<List<Task>> =
    if (source == null) this else combine(this, source.state) { tasks, work ->
        if (!work.filtering) tasks else if (parentOf != null) work.filter(tasks, parentOf) else work.filter(tasks)
    }
