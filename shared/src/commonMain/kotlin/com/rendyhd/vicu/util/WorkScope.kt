package com.rendyhd.vicu.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Which device mode lists what (docs/cross-app-semantics-v1.md section 22.4). */
enum class WorkMode(val key: String) {
    ALL("all"),
    WORK("work"),
    PERSONAL("personal"),
}

/** A project as the work rules see it. */
data class WorkProject(val id: Long, val parentProjectId: Long? = null)

/** What work is on this account, read once from the synced values and the project list. */
data class WorkScope(
    /** The work label, or null when none is set or the label it names is gone. */
    val label: Long? = null,
    val projects: Set<Long> = emptySet(),
) {
    companion object {
        val NONE = WorkScope()
    }
}

/** The device settings the mode reads (the behaviour preferences). */
data class WorkModeSwitches(
    val workModeEnabled: Boolean = false,
    val workMode: String? = null,
    val proOnlyInWork: Boolean = false,
    val standaloneMode: Boolean = false,
)

/** A server clause narrowing a listing, or none; [empty] means the mode shows nothing, so nothing is asked. */
data class WorkModeClause(val clause: String?, val empty: Boolean)

/**
 * Work and Personal (docs/cross-app-semantics-v1.md section 22): which tasks and projects are work,
 * and what a device in Work or Personal mode shows. A port of the desktop's `src/shared/work-scope.ts`;
 * both run the `workMode` vectors of the cross-app fixture.
 */
object WorkScopeRules {
    /** The synced key of the work label. */
    const val WORK_LABEL_KEY = "work.label"
    private const val WORK_PROJECT_PREFIX = "work.project."

    /** The value of `pro.area` that means "the work projects". */
    const val PRO_AREA_WORK = "work"

    private const val MAX_SAFE_INTEGER = 9007199254740991L

    // --- Keys and values (section 22.1) ---

    fun workProjectKey(projectId: Long): String = "$WORK_PROJECT_PREFIX$projectId"

    /** The project id of a `work.project.<id>` key, or null for any other key. */
    fun workProjectIdOfKey(key: String): Long? {
        if (!key.startsWith(WORK_PROJECT_PREFIX)) return null
        val rest = key.substring(WORK_PROJECT_PREFIX.length)
        if (rest.isEmpty() || rest[0] !in '1'..'9' || !rest.all { it in '0'..'9' }) return null
        val id = rest.toLongOrNull() ?: return null
        return id.takeIf { it <= MAX_SAFE_INTEGER }
    }

    /** The work label's id: a whole number above 0. Anything else is no label. */
    fun readWorkLabel(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (value is JsonNull || primitive.isString) return null
        val number = primitive.content.toDoubleOrNull() ?: return null
        if (number != kotlin.math.floor(number) || number < 1.0 || number > MAX_SAFE_INTEGER.toDouble()) return null
        return number.toLong()
    }

    /** A project's own entry: `true` (work), `false` (not work), anything else is no entry. */
    fun readWorkProjectEntry(value: JsonElement?): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        if (value is JsonNull || primitive.isString) return null
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** A value that may be written under `work.label` or `work.project.<id>`. */
    fun isWritableWorkValue(key: String, value: JsonElement?): Boolean {
        if (value == null || value is JsonNull) return key == WORK_LABEL_KEY || workProjectIdOfKey(key) != null
        if (key == WORK_LABEL_KEY) return readWorkLabel(value) != null
        return workProjectIdOfKey(key) != null && readWorkProjectEntry(value) != null
    }

    /** True for one of the two keys. */
    fun isWorkKey(key: String): Boolean = key == WORK_LABEL_KEY || workProjectIdOfKey(key) != null

    // --- Which projects are work (section 22.2) ---

    /**
     * The projects that are work: a project's own entry decides, else the entry of its nearest
     * ancestor that has one, else it is not work. The walk ends at a parent that is not given and
     * at a loop. The Inbox is never work and its entry is not used, also not by its subprojects.
     */
    fun workProjectIds(projects: Iterable<WorkProject>, entryOf: (Long) -> JsonElement?, inboxProjectId: Long?): Set<Long> {
        val byId = LinkedHashMap<Long, WorkProject>()
        for (project in projects) byId[project.id] = project
        val work = LinkedHashSet<Long>()
        for (project in byId.values) {
            var current: WorkProject? = project
            val seen = HashSet<Long>()
            while (current != null && current.id !in seen) {
                seen.add(current.id)
                if (current.id != inboxProjectId) {
                    val entry = readWorkProjectEntry(entryOf(current.id))
                    if (entry != null) {
                        if (entry) work.add(project.id)
                        break
                    }
                }
                val parent = current.parentProjectId
                current = if (parent != null && parent > 0) byId[parent] else null
            }
        }
        if (inboxProjectId != null) work.remove(inboxProjectId)
        return work
    }

    /** The scope from the synced entries and the project list; a label that is gone counts as none. */
    fun scopeOf(
        entries: Map<String, JsonElement?>,
        projects: Iterable<WorkProject>,
        inboxProjectId: Long?,
        labelIds: Set<Long>?,
    ): WorkScope {
        val label = readWorkLabel(entries[WORK_LABEL_KEY])?.takeIf { labelIds == null || it in labelIds }
        return WorkScope(label, workProjectIds(projects, { entries[workProjectKey(it)] }, inboxProjectId))
    }

    /**
     * The entry to write when the person marks a project (section 22.7): none when the project
     * would be what they want without one, else what they want.
     */
    fun workEntryToWrite(want: Boolean, parentIsWork: Boolean): Boolean? = if (want == parentIsWork) null else want

    // --- Which tasks are work (section 22.3) ---

    /** A task is work when its project is work or it carries the work label. */
    fun isWorkTask(projectId: Long?, labelIds: Iterable<Long>?, scope: WorkScope): Boolean {
        if (projectId != null && projectId in scope.projects) return true
        val label = scope.label ?: return false
        return labelIds != null && labelIds.any { it == label }
    }

    // --- The mode (section 22.4) ---

    /** A stored mode; anything else is [WorkMode.ALL]. */
    fun parseWorkMode(value: String?): WorkMode = when (value) {
        "work" -> WorkMode.WORK
        "personal" -> WorkMode.PERSONAL
        else -> WorkMode.ALL
    }

    /** The mode in effect: [WorkMode.ALL] while the feature is off or the device is in standalone mode. */
    fun effectiveWorkMode(config: WorkModeSwitches?): WorkMode {
        if (config == null || !config.workModeEnabled || config.standaloneMode) return WorkMode.ALL
        return parseWorkMode(config.workMode)
    }

    /** Whether the mode lists a task: everything in All, work in Work, the rest in Personal. */
    fun passesWorkMode(projectId: Long?, labelIds: Iterable<Long>?, scope: WorkScope, mode: WorkMode): Boolean {
        if (mode == WorkMode.ALL) return true
        return isWorkTask(projectId, labelIds, scope) == (mode == WorkMode.WORK)
    }

    /**
     * The tasks of a list the mode shows, in the list's order. [parentOf] names the task a row is
     * nested under in this list (null for a row of its own): a row nested under a shown row is shown
     * with it, whatever it is itself.
     */
    fun <T> shownInWorkMode(
        tasks: List<T>,
        scope: WorkScope,
        mode: WorkMode,
        idOf: (T) -> Long,
        projectOf: (T) -> Long?,
        labelsOf: (T) -> Iterable<Long>?,
        parentOf: ((T) -> Long?)? = null,
    ): List<T> {
        if (mode == WorkMode.ALL) return tasks.toList()
        val byId = HashMap<Long, T>()
        for (task in tasks) byId[idOf(task)] = task
        val decided = HashMap<Long, Boolean>()
        fun shown(task: T, trail: MutableSet<Long>): Boolean {
            val id = idOf(task)
            decided[id]?.let { return it }
            var result = passesWorkMode(projectOf(task), labelsOf(task), scope, mode)
            if (!result && parentOf != null && id !in trail) {
                trail.add(id)
                val parentId = parentOf(task)
                val parent = if (parentId != null) byId[parentId] else null
                result = parent != null && shown(parent, trail)
            }
            decided[id] = result
            return result
        }
        return tasks.filter { shown(it, HashSet()) }
    }

    // --- The project list (section 22.5) ---

    /**
     * The projects the mode lists: in Work the work projects, in Personal the others, and in both a
     * project above one that is listed, so the tree stays whole. The Inbox is always listed.
     */
    fun projectsShownInMode(
        projects: Iterable<WorkProject>,
        work: Set<Long>,
        mode: WorkMode,
        inboxProjectId: Long?,
    ): Set<Long> {
        val byId = LinkedHashMap<Long, WorkProject>()
        for (project in projects) byId[project.id] = project
        val shown = LinkedHashSet<Long>()
        for (project in byId.values) {
            val own = mode == WorkMode.ALL || project.id == inboxProjectId || (project.id in work) == (mode == WorkMode.WORK)
            if (!own) continue
            var current: WorkProject? = project
            while (current != null && current.id !in shown) {
                shown.add(current.id)
                val parent = current.parentProjectId
                current = if (parent != null && parent > 0) byId[parent] else null
            }
        }
        return shown
    }

    // --- New tasks (section 22.6) ---

    /**
     * The label a task made in this mode gets on top of its own: the work label, in Work mode, for
     * a task that would not be work without it. Null when nothing is added.
     */
    fun workLabelForNewTask(projectId: Long?, labelIds: Iterable<Long>?, scope: WorkScope, mode: WorkMode): Long? {
        val label = scope.label
        if (mode != WorkMode.WORK || label == null) return null
        return if (isWorkTask(projectId, labelIds, scope)) null else label
    }

    /** True when a task made in Work mode stays Personal, because there is no work label to give it. */
    fun newTaskStaysPersonal(projectId: Long?, labelIds: Iterable<Long>?, scope: WorkScope, mode: WorkMode): Boolean =
        mode == WorkMode.WORK && scope.label == null && !isWorkTask(projectId, labelIds, scope)

    // --- Pro (section 22.8) ---

    /**
     * Whether the mode lets Pro show: with "Pro only in Work" on, a device in Personal mode acts as
     * a device with Pro off (section 21.1). Pro's own switch is asked separately.
     */
    fun proAllowedInWorkMode(config: WorkModeSwitches?): Boolean =
        !(config?.proOnlyInWork == true && effectiveWorkMode(config) == WorkMode.PERSONAL)

    fun isWorkProArea(value: JsonElement?): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        return value !is JsonNull && primitive.isString && primitive.content == PRO_AREA_WORK
    }

    // --- Server filters (section 22.9) ---

    /**
     * A filter clause that narrows a server listing to a superset of what the mode shows, or null
     * when the listing cannot be narrowed. `empty` means the mode shows nothing, so nothing needs
     * asking. The device still applies the rule to the answer.
     */
    fun workModeClause(scope: WorkScope, mode: WorkMode): WorkModeClause {
        if (mode == WorkMode.ALL) return WorkModeClause(null, false)
        val ids = scope.projects.sorted()
        if (mode == WorkMode.PERSONAL) {
            return WorkModeClause(if (ids.isNotEmpty()) "project not in ${ids.joinToString(", ")}" else null, false)
        }
        val parts = mutableListOf<String>()
        if (ids.isNotEmpty()) parts.add("project in ${ids.joinToString(", ")}")
        scope.label?.let { parts.add("labels = $it") }
        if (parts.isEmpty()) return WorkModeClause(null, true)
        return WorkModeClause(if (parts.size == 1) parts[0] else "(${parts.joinToString(" || ")})", false)
    }
}
