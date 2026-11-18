package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.remote.api.ProjectViewDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.SavedFilterImport
import com.rendyhd.vicu.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns the account's Vikunja saved filters and project views into custom lists (cross-app
 * semantics 3.7 and 3.9): every saved filter on this device's first connect, the sources the
 * person picks after that, and importing a source again, which updates its list in place.
 */
class SavedFilterImporter(
    private val api: VikunjaApiService,
    private val store: SavedFilterStore,
    private val customLists: CustomListRepository,
    private val messages: AppMessages? = null,
    /** Runs the snackbar's Undo, which can come after the screen that answered is gone. */
    private val appScope: CoroutineScope? = null,
    private val newId: () -> String = ::randomUuid,
) {
    /**
     * One source of the review sheet, with what importing it would do. [existing] is the list that
     * holds it already and [comparison] how that list compares with a new import of it.
     */
    data class Preview(
        val key: String,
        val title: String,
        val rule: String,
        val result: SavedFilterImport.Result,
        val importedBefore: Boolean,
        val filterId: Long? = null,
        val view: SavedFilterImport.ViewProject? = null,
        val existing: CustomList? = null,
        val comparison: SavedFilterImport.Comparison? = null,
    ) {
        val isView: Boolean get() = view != null

        /** The new import with the id of [existing], ready for Overwrite or Skip. */
        val fresh: CustomList? get() = result.list?.let { list -> existing?.let { list.copy(id = it.id) } ?: list }
    }

    /** Overwrite or Skip, for one source that has a list (section 3.7). */
    enum class Answer { OVERWRITE, SKIP }

    /** The filters on the server that were never imported and not put off with Not now. */
    val offer: Flow<List<SavedFilterRef>> =
        combine(store.known, customLists.importedSavedFilters, store.dismissed) { known, imported, dismissed ->
            known.filter { it.id !in imported && it.id !in dismissed }
        }

    /**
     * Imported filters a read after a sync found changed in Vikunja, not answered and not put off
     * since ("Changed in Vikunja", section 3.7).
     */
    val changedOffer: Flow<List<SavedFilterRef>> =
        combine(store.known, store.changed, store.changedDismissed, customLists.lists) { known, changed, dismissed, lists ->
            known.filter { ref ->
                val base = lists.firstOrNull { it.savedFilterId == ref.id }?.importBase ?: return@filter false
                ref.updated.isNotEmpty() && changed[ref.id] == ref.updated && dismissed[ref.id] != ref.updated && base.updated != ref.updated
            }
        }

    /** Every filter on the server, as the last project refresh found them. */
    val known: Flow<List<SavedFilterRef>> = store.known

    /**
     * The step after a sync (SyncEngine): drop duplicate imports, on this device's first connect
     * import every readable filter not imported yet, and after that look for imported filters that
     * changed in Vikunja. [listsSynced] is the custom-list sync's result: without a read of the
     * carrier, the import record is not known and nothing is imported. Returns whether lists were
     * added (the caller syncs them right away).
     */
    suspend fun afterSync(listsSynced: CustomListSyncStatus): Boolean {
        if (listsSynced != CustomListSyncStatus.Idle) return false
        customLists.dropDuplicateImports()
        if (store.isFirstConnectDone()) {
            checkChanged()
            return false
        }
        val imported = customLists.importedSavedFilters.first()
        val wanted = store.known.first().filter { it.id !in imported }
        val previews = previews(wanted) ?: return false
        val added = import(previews.filter { it.result.list != null && it.existing == null })
        store.markFirstConnectDone()
        return added > 0
    }

    /**
     * Reads each imported filter whose `updated` moved past its list's base, once per new value,
     * and notes the ones changed in Vikunja for [changedOffer].
     */
    private suspend fun checkChanged() {
        val lists = customLists.lists.first()
        val checked = store.checked.first()
        for (ref in store.known.first()) {
            val list = lists.firstOrNull { it.savedFilterId == ref.id } ?: continue
            val base = list.importBase ?: continue
            if (ref.updated.isEmpty() || ref.updated == base.updated || checked[ref.id] == ref.updated) continue
            val fresh = try {
                SavedFilterImport.convert(api.getSavedFilter(ref.id), list.id).list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Reading a changed saved filter failed: ${e.message}")
                return
            }
            val status = fresh?.let { SavedFilterImport.compare(list, it).status }
            if (status == SavedFilterImport.Change.VIKUNJA || status == SavedFilterImport.Change.BOTH) {
                store.markChanged(ref.id, ref.updated)
            }
            store.markChecked(ref.id, ref.updated)
        }
    }

    /** The review sheet's rows for [filters], read from the server; null when that failed. */
    suspend fun previews(filters: List<SavedFilterRef>): List<Preview>? {
        val imported = customLists.importedSavedFilters.first()
        val lists = customLists.lists.first()
        return try {
            filters.map { ref ->
                val dto = api.getSavedFilter(ref.id)
                val existing = lists.firstOrNull { it.savedFilterId == ref.id }
                preview(
                    key = "filter:${ref.id}",
                    title = ref.title,
                    rule = dto.filters.filter.ifBlank { dto.filters.s },
                    result = SavedFilterImport.convert(dto, existing?.id ?: newId()),
                    importedBefore = ref.id in imported || existing != null,
                    existing = existing,
                ).copy(filterId = ref.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Reading saved filters failed: ${e.message}")
            null
        }
    }

    /**
     * The review sheet's rows for the account's project views (section 3.9): every view that is
     * offered, and every view a list holds. Null when the server could not be read.
     */
    suspend fun viewPreviews(): List<Preview>? {
        val lists = customLists.lists.first()
        return try {
            val projects = api.getProjectsAndSavedFilters(includeArchived = false).first
                .filter { it.id >= 1 }
                .sortedBy { it.position }
            val gate = Semaphore(VIEW_READS)
            val views = coroutineScope {
                projects.map { project ->
                    async { gate.withPermit { project to api.getProjectViews(project.id) } }
                }.awaitAll()
            }
            views.flatMap { (dto, projectViews) ->
                val project = SavedFilterImport.ViewProject(dto.id, dto.title, dto.isArchived)
                projectViews.sortedBy { it.position }.mapNotNull { view ->
                    val existing = lists.firstOrNull { it.projectView?.viewId == view.id }
                    if (existing == null && !SavedFilterImport.isOffered(project, view)) return@mapNotNull null
                    viewPreview(project, view, existing)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Reading project views failed: ${e.message}")
            null
        }
    }

    private fun viewPreview(project: SavedFilterImport.ViewProject, view: ProjectViewDto, existing: CustomList?): Preview {
        val query = view.filter
        return preview(
            key = "view:${view.id}",
            title = "${project.title.trim()}: ${view.title.trim()}",
            rule = query?.filter?.ifBlank { query.s }.orEmpty(),
            result = SavedFilterImport.convertView(project, view, existing?.id ?: newId()),
            importedBefore = existing != null,
            existing = existing,
        ).copy(view = project)
    }

    private fun preview(
        key: String,
        title: String,
        rule: String,
        result: SavedFilterImport.Result,
        importedBefore: Boolean,
        existing: CustomList?,
    ): Preview {
        val comparison = result.list?.let { fresh -> existing?.let { SavedFilterImport.compare(it, fresh) } }
        return Preview(key, title, rule, result, importedBefore, existing = existing, comparison = comparison)
    }

    /**
     * Imports [previews] as new lists (sources without a list); says so on the app-wide snackbar.
     * The count added.
     */
    suspend fun import(previews: List<Preview>): Int {
        val lists = previews.filter { it.existing == null }.mapNotNull { it.result.list }
        if (lists.isEmpty()) return 0
        customLists.importLists(lists, lists.mapNotNull { it.savedFilterId })
        val text = if (lists.size == 1) "Imported 1 list" else "Imported ${lists.size} lists"
        messages?.postOpeningList(text, lists.first().id)
        return lists.size
    }

    /**
     * Writes the answers for sources that have a list, and the new lists of the same Import
     * ([added]), in one change, with one Undo that removes the new lists and puts every answered
     * list back as it was (section 3.7).
     */
    suspend fun answer(answers: List<Pair<Preview, Answer>>, added: List<Preview> = emptyList()) {
        val writes = answers.mapNotNull { (preview, answer) ->
            val list = preview.existing ?: return@mapNotNull null
            val fresh = preview.fresh ?: return@mapNotNull null
            Triple(list, if (answer == Answer.OVERWRITE) SavedFilterImport.overwrite(list, fresh) else SavedFilterImport.skip(list, fresh), answer)
        }
        val newLists = added.filter { it.existing == null }.mapNotNull { it.result.list }
        if (writes.isEmpty() && newLists.isEmpty()) return
        val lists = newLists + writes.map { it.second }
        customLists.importLists(lists, lists.mapNotNull { it.savedFilterId })
        val updated = writes.count { it.third == Answer.OVERWRITE }
        val skipped = writes.size - updated
        val parts = buildList {
            if (newLists.isNotEmpty()) add(if (newLists.size == 1) "Imported 1 list" else "Imported ${newLists.size} lists")
            if (updated > 0) add(if (updated == 1) "updated 1 list" else "updated $updated lists")
            if (skipped > 0) add("skipped $skipped")
        }
        val text = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        val before = writes.map { it.first }
        val scope = appScope
        if (scope == null) {
            messages?.post(text)
        } else {
            messages?.post(text, actionLabel = "Undo") {
                scope.launch {
                    newLists.forEach { customLists.delete(it.id) }
                    if (before.isNotEmpty()) customLists.importLists(before, emptyList())
                }
            }
        }
    }

    /** Hides the offer for [filters] and the changes in [changed] on this device until another appears. */
    suspend fun dismiss(filters: List<SavedFilterRef>, changed: List<SavedFilterRef> = emptyList()) {
        if (filters.isNotEmpty()) store.dismiss(filters.map { it.id })
        if (changed.isNotEmpty()) store.dismissChanged(changed.associate { it.id to it.updated })
    }

    /** Whether the first-open notice of the imported list [listId] was closed on this device. */
    val noticeClosed: Flow<Set<String>> = store.noticeClosed

    suspend fun closeNotice(listId: String) = store.closeNotice(listId)

    private companion object {
        const val TAG = "SavedFilterImporter"
        const val VIEW_READS = 6
    }
}
