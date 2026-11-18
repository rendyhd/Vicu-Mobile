package com.rendyhd.vicu.data.sync

import com.rendyhd.vicu.auth.authTestJson
import com.rendyhd.vicu.auth.authTestJsonHeaders
import com.rendyhd.vicu.data.local.SavedFilterRef
import com.rendyhd.vicu.data.local.SavedFilterStore
import com.rendyhd.vicu.data.mapper.ProjectMapper
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.data.repository.FakePendingActionDao
import com.rendyhd.vicu.data.repository.FakeProjectDao
import com.rendyhd.vicu.data.repository.InMemoryPreferencesDataStore
import com.rendyhd.vicu.domain.model.CustomList
import com.rendyhd.vicu.domain.model.CustomListSyncStatus
import com.rendyhd.vicu.domain.repository.CustomListRepository
import com.rendyhd.vicu.ui.components.shared.RuleEditing
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.SavedFilterImport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved filters (cross-app semantics 3.7): never projects, imported once on first connect. */
class SavedFiltersTest {

    private fun page(vararg items: String) =
        """{"items":[${items.joinToString(",")}],"total":${items.size},"page":1,"per_page":100,"total_pages":1}"""

    private val projects = page(
        """{"id":4,"title":"Home"}""",
        """{"id":-1,"title":"Favorites"}""",
        """{"id":-2,"title":"High Priority","updated":"2026-11-05T09:00:00Z"}""",
        """{"id":-4,"title":"Broken"}""",
    )

    /** What `filters/1` answers; a test changes it to change the filter in Vikunja. */
    private var filterOne =
        """{"id":1,"title":"High Priority","updated":"2026-11-05T09:00:00Z","filters":{"filter":"done = false && priority > 2","sort_by":["priority"],"order_by":["desc"]}}"""

    private val homeViews = page(
        """{"id":21,"project_id":4,"title":"List","view_kind":"list","filter":{"filter":"done = false","s":"","sort_by":null,"order_by":null,"filter_include_nulls":false},"updated":"2026-10-01T08:00:00Z"}""",
        """{"id":22,"project_id":4,"title":"All items","view_kind":"list","filter":null,"updated":"2026-11-03T10:00:00Z"}""",
        """{"id":23,"project_id":4,"title":"Kanban","view_kind":"kanban","filter":null,"updated":"2026-10-01T08:00:00Z"}""",
    )

    private fun api(requests: MutableList<String> = mutableListOf()): VikunjaApiService {
        val client = HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/projects" -> respond(projects, HttpStatusCode.OK, authTestJsonHeaders)
                    "/projects/4/views" -> respond(homeViews, HttpStatusCode.OK, authTestJsonHeaders)
                    "/filters/1" -> respond(filterOne, HttpStatusCode.OK, authTestJsonHeaders)
                    "/filters/3" -> respond("""{"id":3,"title":"Broken","filters":{"filter":"priority >> 2"}}""", HttpStatusCode.OK, authTestJsonHeaders)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) { install(ContentNegotiation) { json(authTestJson) } }
        return VikunjaApiService(client, authTestJson)
    }

    /** Lists and the import record, the way the repository keeps them. */
    private class FakeLists : CustomListRepository {
        val all = MutableStateFlow<List<CustomList>>(emptyList())
        val imported = MutableStateFlow<Set<Long>>(emptySet())
        override val lists: Flow<List<CustomList>> = all
        override val syncStatus: StateFlow<CustomListSyncStatus> = MutableStateFlow(CustomListSyncStatus.Idle)
        override val importedSavedFilters: Flow<Set<Long>> = imported.map { it }
        override suspend fun importLists(lists: List<CustomList>, filterIds: Collection<Long>) {
            // A list that is there already is rewritten in place, as the repository does.
            val byId = lists.associateBy { it.id }
            all.value = all.value.map { byId[it.id] ?: it } + lists.filter { list -> all.value.none { it.id == list.id } }
            imported.value = imported.value + filterIds
        }
        override suspend fun upsert(customList: CustomList) {
            all.value = all.value.filterNot { it.id == customList.id } + customList
        }
        override suspend fun delete(id: String) {
            all.value = all.value.filterNot { it.id == id }
        }
        override suspend fun reorder(fromIndex: Int, toIndex: Int) = Unit
        override suspend fun clearLocal() = Unit
        override suspend fun sync(): CustomListSyncStatus = CustomListSyncStatus.Idle
    }

    @Test
    fun `the project refresh keeps pseudo-projects out and remembers the saved filters`() = runTest {
        val dao = FakeProjectDao()
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        ProjectRefresher(dao, FakePendingActionDao(), api(), ProjectMapper(), store).refresh()

        assertEquals(listOf(4L), dao.getAllIds())
        assertEquals(listOf(SavedFilterRef(1, "High Priority", "2026-11-05T09:00:00Z"), SavedFilterRef(3, "Broken")), store.known.first())
    }

    @Test
    fun `getAllProjects never returns a pseudo-project`() = runTest {
        assertEquals(listOf(4L), api().getAllProjects().map { it.id })
    }

    @Test
    fun `first connect imports every readable filter once`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists()
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()
        var id = 0
        val importer = SavedFilterImporter(apiService, store, lists, newId = { "list-${++id}" })

        assertTrue(importer.afterSync(CustomListSyncStatus.Idle))

        val imported = lists.all.value.single()
        assertEquals("High Priority", imported.name)
        assertEquals(1L, imported.savedFilterId)
        assertEquals("done = false && priority > 2", imported.filter.rule)
        assertEquals("priority" to "desc", imported.filter.sortBy to imported.filter.orderBy)
        assertEquals(setOf(1L), lists.imported.value)
        assertTrue(store.isFirstConnectDone())

        // A later sync imports nothing again, and the broken filter stays on offer.
        assertFalse(importer.afterSync(CustomListSyncStatus.Idle))
        assertEquals(1, lists.all.value.size)
        assertEquals(listOf(SavedFilterRef(3, "Broken")), importer.offer.first())
    }

    @Test
    fun `nothing is imported before the lists were read`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists()
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()
        val importer = SavedFilterImporter(apiService, store, lists)

        assertFalse(importer.afterSync(CustomListSyncStatus.Offline("offline")))
        assertTrue(lists.all.value.isEmpty())
        assertFalse(store.isFirstConnectDone())
    }

    @Test
    fun `a filter another device imported is not imported again`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists().apply { imported.value = setOf(1L) }
        val apiService = api()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()

        assertFalse(SavedFilterImporter(apiService, store, lists).afterSync(CustomListSyncStatus.Idle))
        assertTrue(lists.all.value.isEmpty())
        assertTrue(store.isFirstConnectDone())
    }

    @Test
    fun `Not now hides the offer until another filter appears`() = runTest {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val importer = SavedFilterImporter(api(), store, FakeLists())
        store.setKnown(listOf(SavedFilterRef(1, "A")))
        importer.dismiss(importer.offer.first())
        assertTrue(importer.offer.first().isEmpty())

        store.setKnown(listOf(SavedFilterRef(1, "A"), SavedFilterRef(2, "B")))
        assertEquals(listOf(SavedFilterRef(2, "B")), importer.offer.first())
    }

    // --- Importing again (section 3.7) and project views (section 3.9) -----------------------------

    /** A store and lists after the first connect imported filter 1 as "list-1". */
    private suspend fun imported(apiService: VikunjaApiService): Triple<SavedFilterStore, FakeLists, SavedFilterImporter> {
        val store = SavedFilterStore(InMemoryPreferencesDataStore())
        val lists = FakeLists()
        ProjectRefresher(FakeProjectDao(), FakePendingActionDao(), apiService, ProjectMapper(), store).refresh()
        var id = 0
        val importer = SavedFilterImporter(apiService, store, lists, newId = { "list-${++id}" })
        importer.afterSync(CustomListSyncStatus.Idle)
        return Triple(store, lists, importer)
    }

    @Test
    fun `the first import records its base`() = runTest {
        val (_, lists, _) = imported(api())
        val base = lists.all.value.single().importBase
        assertEquals("2026-11-05T09:00:00Z", base?.updated)
        assertEquals("done = false && priority > 2", base?.rule)
    }

    @Test
    fun `importing again compares and overwrites in place with Undo`() = runTest {
        val apiService = api()
        val (store, lists, _) = imported(apiService)
        val messages = AppMessages()
        val importer = SavedFilterImporter(apiService, store, lists, messages, appScope = this, newId = { "unused" })
        // The person renames the list, and the filter's rule changes in Vikunja.
        lists.upsert(lists.all.value.single().copy(name = "Prio"))
        filterOne = filterOne.replace("priority > 2", "priority > 3").replace("09:00:00Z", "10:00:00Z")

        val preview = importer.previews(store.known.first()).orEmpty().first { it.filterId == 1L }
        assertEquals("list-1", preview.existing?.id)
        assertEquals(SavedFilterImport.Change.BOTH, preview.comparison?.status)
        assertEquals(SavedFilterImport.Change.VICU, preview.comparison?.parts?.get(SavedFilterImport.Part.NAME))
        assertEquals(SavedFilterImport.Change.VIKUNJA, preview.comparison?.parts?.get(SavedFilterImport.Part.RULE))

        importer.answer(listOf(preview to SavedFilterImporter.Answer.OVERWRITE))
        val updated = lists.all.value.single()
        assertEquals("list-1", updated.id)
        assertEquals("High Priority", updated.name)
        assertEquals("done = false && priority > 3", updated.filter.rule)
        assertEquals("2026-11-05T10:00:00Z", updated.importBase?.updated)

        val message = messages.messages.first()
        assertEquals("Updated 1 list", message.text)
        message.onAction?.invoke()
        advanceUntilIdle()
        assertEquals("Prio", lists.all.value.single().name)
        assertEquals("done = false && priority > 2", lists.all.value.single().filter.rule)
    }

    @Test
    fun `Skip keeps the list and takes the new base`() = runTest {
        val apiService = api()
        val (store, lists, importer) = imported(apiService)
        filterOne = filterOne.replace("priority > 2", "priority > 3").replace("09:00:00Z", "10:00:00Z")
        val preview = importer.previews(store.known.first()).orEmpty().first { it.filterId == 1L }
        importer.answer(listOf(preview to SavedFilterImporter.Answer.SKIP))

        val kept = lists.all.value.single()
        assertEquals("done = false && priority > 2", kept.filter.rule)
        assertEquals("2026-11-05T10:00:00Z", kept.importBase?.updated)
        val again = importer.previews(store.known.first()).orEmpty().first { it.filterId == 1L }
        assertEquals(SavedFilterImport.Change.VICU, again.comparison?.status)
    }

    @Test
    fun `a filter changed in Vikunja is offered once per change`() = runTest {
        val requests = mutableListOf<String>()
        val apiService = api(requests)
        val (store, lists, importer) = imported(apiService)
        assertTrue(importer.changedOffer.first().isEmpty())

        // The filter changes: its pseudo-project's updated moves past the list's base.
        filterOne = filterOne.replace("priority > 2", "priority > 3").replace("09:00:00Z", "10:00:00Z")
        store.setKnown(listOf(SavedFilterRef(1, "High Priority", "2026-11-05T10:00:00Z"), SavedFilterRef(3, "Broken")))
        requests.clear()
        importer.afterSync(CustomListSyncStatus.Idle)
        assertEquals(listOf(1L), importer.changedOffer.first().map { it.id })
        assertEquals(listOf("/filters/1"), requests)

        // Read once per change, and Not now hides it.
        requests.clear()
        importer.afterSync(CustomListSyncStatus.Idle)
        assertTrue(requests.isEmpty())
        importer.dismiss(emptyList(), importer.changedOffer.first())
        assertTrue(importer.changedOffer.first().isEmpty())
        assertEquals(1, lists.all.value.size)
    }

    @Test
    fun `a change that only touches what Vicu does not import is not offered`() = runTest {
        val apiService = api()
        val (store, _, importer) = imported(apiService)
        filterOne = filterOne.replace("09:00:00Z", "10:00:00Z")
        store.setKnown(listOf(SavedFilterRef(1, "High Priority", "2026-11-05T10:00:00Z")))
        importer.afterSync(CustomListSyncStatus.Idle)
        assertTrue(importer.changedOffer.first().isEmpty())
    }

    @Test
    fun `project views offered and imported`() = runTest {
        val lists = FakeLists()
        val importer = SavedFilterImporter(api(), SavedFilterStore(InMemoryPreferencesDataStore()), lists, newId = { "view-list" })
        val views = importer.viewPreviews().orEmpty()
        assertEquals(listOf("Home: All items"), views.map { it.title })

        importer.import(views)
        val list = lists.all.value.single()
        assertEquals(22L, list.projectView?.viewId)
        assertEquals(listOf(4L), list.filter.projectIds)
        assertTrue(list.filter.includeDone)

        // Imported, it is listed with its comparison and cannot be ticked while it is the same.
        val again = importer.viewPreviews().orEmpty().single()
        assertEquals("view-list", again.existing?.id)
        assertEquals(SavedFilterImport.Change.SAME, again.comparison?.status)
    }

    // --- The editor's names -------------------------------------------------------------------

    private val labels = listOf(RuleEditing.Named(1, "Errand"), RuleEditing.Named(2, "Home"))
    private val projectNames = listOf(RuleEditing.Named(10, "Work"), RuleEditing.Named(11, "Garden shed"))

    @Test
    fun `the editor shows names and saves ids`() {
        val rule = "labels in 1, 2 && project = 11 && priority > 2"
        val shown = RuleEditing.toDisplay(rule, labels, projectNames)
        assertEquals("labels in Errand, Home && project = Garden shed && priority > 2", shown)
        assertEquals(RuleEditing.Saved.Rule(rule), RuleEditing.fromDisplay(shown, labels, projectNames))
    }

    @Test
    fun `an unknown name or an invalid rule cannot be saved`() {
        val unknown = RuleEditing.fromDisplay("labels = Garage", labels, projectNames)
        assertTrue(unknown is RuleEditing.Saved.Problem && "Garage" in unknown.message)
        assertTrue(RuleEditing.fromDisplay("lables = 1", labels, projectNames) is RuleEditing.Saved.Problem)
        assertEquals(RuleEditing.Saved.Rule(null), RuleEditing.fromDisplay("  ", labels, projectNames))
    }

    @Test
    fun `suggestions follow what is typed`() {
        fun labelsAt(text: String) = RuleEditing.suggest(text, text.length, labels, projectNames).map { it.label }
        assertTrue("due_date" in labelsAt("du"))
        assertTrue("not in" in labelsAt("labels "))
        assertEquals(listOf("Home"), labelsAt("labels in Errand, Ho"))
        assertTrue("now/d" in labelsAt("due_date < "))
        assertEquals(listOf("&&", "||"), labelsAt("priority > 2 "))
    }
}
