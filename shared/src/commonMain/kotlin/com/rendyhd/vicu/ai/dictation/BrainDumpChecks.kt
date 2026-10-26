package com.rendyhd.vicu.ai.dictation

import com.rendyhd.vicu.ai.AiContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Where a brain dump's project ref leads: a new project of the plan, or a project that exists. */
sealed interface ProjectTarget {
    data class New(val ref: String) : ProjectTarget

    data class Existing(val id: Long) : ProjectTarget
}

/**
 * The checks of a brain dump's answer (`docs/ai-assistant-v1.md`, section 4.8.3): the new projects
 * first (limit, refs, title, parent, the same project twice), then the tasks through
 * [DictationChecks] with the brain dump's limit, no list default due date, and `project_ref`.
 * Vectors: `brainDump.checks` of `ai-tools-v1.json`.
 */
object BrainDumpChecks {
    /** [answer] matches `brainDump.answerSchema` (the caller checked it). */
    fun check(answer: JsonObject, context: DictationContext): CheckResult {
        val limits = AiContract.limits
        val dropped = mutableListOf<DroppedField>()
        val all = answer["projects"].orEmpty().map { it.jsonObject }

        // 1. Projects: at most 12; refs renumbered when one is empty or repeated, and a ref names
        // the first project that was given it.
        for (extra in all.drop(limits.brainDumpProjectsMax)) {
            dropped += DroppedField(extra.str("ref"), "project", extra["title"] ?: JsonNull)
        }
        val given = all.take(limits.brainDumpProjectsMax)
        val refs = given.map { it.str("ref").orEmpty() }
        val renumber = refs.any { it.isEmpty() } || refs.toSet().size != refs.size
        val firstWithRef = HashMap<String, Int>()
        refs.forEachIndexed { index, ref -> if (ref.isNotEmpty() && ref !in firstWithRef) firstWithRef[ref] = index }

        val contextById = context.projects.associateBy { it.id }
        val outcomes = HashMap<Int, ProjectTarget>()
        val paths = LinkedHashMap<String, String>()
        val kept = mutableListOf<CheckedProject>()
        given.forEachIndexed { index, project ->
            val ref = if (renumber) "p${index + 1}" else refs[index]

            // 2. Title.
            var title = DictationChecks.oneLine(project.str("title").orEmpty()).trim()
            if (title.isEmpty()) {
                dropped += DroppedField(ref, "project_title", project["title"] ?: JsonNull)
                return@forEachIndexed
            }
            if (title.length > limits.titleMaxChars) {
                val cut = title.lastIndexOf(' ', limits.titleMaxChars)
                title = title.substring(0, if (cut < 0) limits.titleMaxChars else cut)
            }

            // 3. Parent: a project before this one, or one of the context.
            val parentRef = project.str("parent_ref")?.takeIf { it.isNotEmpty() }
            val parentId = (project["parent_id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
            var parent: ProjectTarget? = null
            if (parentRef != null) {
                val at = firstWithRef[parentRef]
                parent = if (at != null && at < index) outcomes[at] else null
                if (parent == null) dropped += DroppedField(ref, "parent", project.getValue("parent_ref"))
            } else if (parentId != null) {
                val id = if (parentId.isString) null else parentId.longOrNull
                parent = id?.takeIf { it in contextById }?.let { ProjectTarget.Existing(it) }
                if (parent == null) dropped += DroppedField(ref, "parent", parentId)
            }

            // 4. The same project twice: a context project or an earlier new one with this path.
            val parentPath = when (parent) {
                is ProjectTarget.Existing -> contextById.getValue(parent.id).path
                is ProjectTarget.New -> paths.getValue(parent.ref)
                null -> null
            }
            val path = if (parentPath == null) title else "$parentPath / $title"
            val key = path.lowercase()
            val existing = context.projects.firstOrNull { it.path.lowercase() == key }
            val earlier = paths.entries.firstOrNull { it.value.lowercase() == key }?.key
            outcomes[index] = when {
                existing != null -> ProjectTarget.Existing(existing.id)
                earlier != null -> ProjectTarget.New(earlier)
                else -> {
                    paths[ref] = path
                    kept += CheckedProject(ref, title, (parent as? ProjectTarget.Existing)?.id, (parent as? ProjectTarget.New)?.ref)
                    ProjectTarget.New(ref)
                }
            }
        }

        // 5. Tasks.
        val tasks = DictationChecks.check(
            answer,
            context.copy(defaultDue = null),
            draftsMax = limits.brainDumpTasksMax,
            resolveProjectRef = { ref -> firstWithRef[ref]?.let { outcomes[it] } },
        )
        return tasks.copy(projects = kept, dropped = dropped + tasks.dropped)
    }

    /**
     * Removing a new project by hand (section 4.8.5): its tasks and new subprojects move to its
     * parent, or at the top its tasks to the default project and its subprojects to the top.
     */
    fun removeProject(
        ref: String,
        projects: List<CheckedProject>,
        drafts: List<CheckedDraft>,
        defaultProjectId: Long,
    ): Pair<List<CheckedProject>, List<CheckedDraft>> {
        val removed = projects.firstOrNull { it.ref == ref } ?: return projects to drafts
        val keptProjects = projects.filterNot { it.ref == ref }.map { p ->
            if (p.parentRef == ref) p.copy(parentId = removed.parentId, parentRef = removed.parentRef) else p
        }
        val movedDrafts = drafts.map { d ->
            when {
                d.projectRef != ref -> d
                removed.parentRef != null -> d.copy(projectRef = removed.parentRef)
                else -> d.copy(projectRef = null, projectId = removed.parentId ?: defaultProjectId)
            }
        }
        return keptProjects to movedDrafts
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonElement?.orEmpty(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
}
