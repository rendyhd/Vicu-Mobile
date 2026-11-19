package com.rendyhd.vicu.ai.notes

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.AiProviders
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.ai.dictation.BrainDumpChecks
import com.rendyhd.vicu.ai.dictation.CheckResult
import com.rendyhd.vicu.ai.dictation.CheckedDraft
import com.rendyhd.vicu.ai.dictation.DictationContext
import com.rendyhd.vicu.ai.dictation.DictationChecks
import com.rendyhd.vicu.ai.dictation.DraftLabel
import com.rendyhd.vicu.ai.dictation.PlanWriter
import com.rendyhd.vicu.ai.provider.LlmException
import com.rendyhd.vicu.data.repository.ProLedgerRepository
import com.rendyhd.vicu.data.repository.ProWorldSource
import com.rendyhd.vicu.domain.model.CollabState
import com.rendyhd.vicu.domain.repository.CollaborationRepository
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.i18n.UiText
import com.rendyhd.vicu.resources.Res
import com.rendyhd.vicu.resources.notes_added
import com.rendyhd.vicu.resources.notes_file_image
import com.rendyhd.vicu.resources.notes_file_password
import com.rendyhd.vicu.resources.notes_file_size
import com.rendyhd.vicu.resources.notes_file_type
import com.rendyhd.vicu.resources.notes_file_unreadable
import com.rendyhd.vicu.resources.notes_more_pdf_pages
import com.rendyhd.vicu.resources.notes_not_ready
import com.rendyhd.vicu.resources.notes_nothing
import com.rendyhd.vicu.resources.notes_open
import com.rendyhd.vicu.resources.notes_plan_assign_failed
import com.rendyhd.vicu.resources.notes_plan_nothing_ticked
import com.rendyhd.vicu.resources.notes_read_first
import com.rendyhd.vicu.resources.notes_still_open
import com.rendyhd.vicu.resources.notes_still_open_untitled
import com.rendyhd.vicu.resources.notes_too_many_pages
import com.rendyhd.vicu.resources.notes_undo
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.pro.People
import com.rendyhd.vicu.util.pro.Person
import com.rendyhd.vicu.util.pro.Promises
import com.rendyhd.vicu.util.randomUuid
import com.rendyhd.vicu.voice.TaskDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the notes sheet shows besides the session itself. */
data class NotesUi(
    val session: NotesSession,
    /** The sheet is on screen (the notes stay kept while it is closed). */
    val open: Boolean = true,
    /** The images agreement is being asked (section 7.1). */
    val askImages: Boolean = false,
    /** The model being asked whether it reads images. */
    val checkingReader: String? = null,
    /** None of the models reads images: the person picks one in Settings. */
    val noReader: Boolean = false,
    val adding: Boolean = false,
    val updating: Boolean = false,
    /** Files that gave no pages, and why. */
    val problems: List<UiText> = emptyList(),
    /** The context the plan was organized with (project names on the cards). */
    val context: DictationContext? = null,
)

/** Who a card can be for (section 4.9.9): the members of shared projects, and Pro's people. */
data class NotesOwnerWorld(
    val collab: CollabState = CollabState(),
    /** Pro people while the promises layer is on, else empty. */
    val people: List<Person> = emptyList(),
) {
    /** The members of [projectId] other than you, when someone else can see it; a new project is nobody's. */
    fun members(projectId: Long?): List<Person> {
        val access = projectId?.let { collab.access(it) }?.takeIf { it.shared } ?: return emptyList()
        return access.members.filter { it.id != collab.me }.map { Person(it.id, it.name.ifBlank { it.username }, listOf(it.username)) }
    }

    fun person(id: Long): Person? = people.firstOrNull { it.id == id }

    /** The card's action and tick: the person's choice, else the default and the filter. */
    fun choice(draft: NotesDraft, session: NotesSession): Pair<OwnerAction, Boolean> {
        val own = session.choices[draft.ref]
        val projectId = if (draft.draft.projectRef != null) null else draft.draft.projectId
        val action = own?.action ?: NotesRules.defaultOwnerAction(draft.owner, members(projectId), people, session.agreed)
        return action to (own?.ticked ?: NotesRules.tickedBy(session.filter, action))
    }
}

/**
 * Notes to tasks across the app (`docs/ai-assistant-v1.md`, section 4.9): the pages (typed, files,
 * PDFs, scans), reading the pictures on the reading model with at most `notes_read_parallel` at a
 * time, organizing the transcript in batches into one plan, the review with an owner and a tick per
 * card, and Add with one Undo. The notes are kept until added or discarded (they survive the app
 * closing); the reading runs on the app's scope, so leaving the sheet or the app for a moment does
 * not stop it.
 */
class NotesController(
    private val service: NotesService,
    private val providers: AiProviders,
    private val settings: AiSettingsStore,
    private val files: NotesFiles,
    private val store: NotesSessionStore,
    private val planWriter: PlanWriter,
    private val tasks: TaskRepository,
    private val collaboration: CollaborationRepository,
    private val proLedger: ProLedgerRepository?,
    proWorld: ProWorldSource?,
    private val messages: AppMessages,
    private val dayClock: DayClock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<NotesUi?>(null)
    val state: StateFlow<NotesUi?> = _state.asStateFlow()

    val owners: StateFlow<NotesOwnerWorld> = combine(
        collaboration.state,
        proWorld?.world ?: flowOf(null),
    ) { collab, world ->
        NotesOwnerWorld(collab, if (world?.promisesOn == true) world.peopleWithAliases else emptyList())
    }.stateIn(scope, SharingStarted.Eagerly, NotesOwnerWorld())

    private var readJob: Job? = null
    private var organizeJob: Job? = null

    init {
        scope.launch { store.forgotten.collect { discard() } }
    }

    private fun today(): LocalDate = dayClock.day.value.date

    /** Changes the session and keeps it. */
    private fun change(edit: (NotesSession) -> NotesSession) {
        val next = _state.value?.let { it.copy(session = edit(it.session)) } ?: return
        _state.value = next
        scope.launch { store.save(next.session) }
    }

    private fun ui(edit: (NotesUi) -> NotesUi) = _state.update { it?.let(edit) }

    /** At app start: notes not added yet come back, closed, with a message that opens them. */
    fun restore() {
        scope.launch {
            if (_state.value != null) return@launch
            val kept = store.load() ?: return@launch
            // A page that was being read when the app closed is read again.
            val session = kept.copy(
                pages = kept.pages.map { if (it.state == PageState.READING) it.copy(state = PageState.UNREAD) else it },
                organizing = false,
            )
            _state.value = NotesUi(session, open = false)
            // The plan's cards name projects: the context is read again for them.
            if (session.plan != null) {
                runCatching { service.context(TaskDefaults(session.projectId, null, session.listName), LocalDate.parse(session.date)) }
                    .getOrNull()?.let { context -> ui { it.copy(context = context) } }
            }
            val title = session.title.trim()
            messages.post(
                if (title.isEmpty()) UiText.Res(Res.string.notes_still_open_untitled) else UiText.Res(Res.string.notes_still_open, title),
                UiText.Res(Res.string.notes_open),
            ) { reopen() }
        }
    }

    /** Opens the notes sheet: the notes still open, or new ones for the list [defaults] describes. */
    fun open(defaults: TaskDefaults = TaskDefaults()) {
        scope.launch { opened(defaults) }
    }

    /** Opens the notes with what was shared to Vicu: its files as pages, else its text as one page. */
    fun openWith(defaults: TaskDefaults, uris: List<String>, text: String?) {
        scope.launch {
            if (!opened(defaults)) return@launch
            if (uris.isNotEmpty()) addFiles(uris)
            if (!text.isNullOrBlank()) addText(text)
        }
    }

    private suspend fun opened(defaults: TaskDefaults): Boolean {
        if (!settings.current().dictationReady) {
            messages.post(UiText.Res(Res.string.notes_not_ready))
            return false
        }
        if (_state.value != null) {
            ui { it.copy(open = true) }
            return true
        }
        val session = NotesSession(date = today().toString(), projectId = defaults.projectId, listName = defaults.listName)
        _state.value = NotesUi(session)
        store.save(session)
        return true
    }

    fun reopen() = ui { it.copy(open = true) }

    /** Closes the sheet; the notes are kept until added or discarded. */
    fun close() = ui { it.copy(open = false, problems = emptyList()) }

    fun discard() {
        readJob?.cancel()
        organizeJob?.cancel()
        _state.value = null
        scope.launch {
            store.save(null)
            files.clear()
        }
    }

    // --- Pages --------------------------------------------------------------------------------

    fun setTitle(title: String) = change { it.copy(title = DictationChecks.oneLine(title)) }

    fun setDate(date: LocalDate) = change { it.copy(date = date.toString()) }

    private fun newPage(kind: PageKind, name: String, page: SourcePage) = NotesSourcePage(
        id = randomUuid(),
        kind = kind,
        name = name,
        text = page.text,
        imagePath = page.imagePath,
        pdfPath = page.pdfPath,
        pdfIndex = page.pdfIndex,
        textLayer = page.textLayer,
        state = if (kind.isImage) PageState.UNREAD else PageState.READ,
    )

    /** Adds pages at the end, at most `notes_pages_max` in all. */
    private fun addPages(pages: List<NotesSourcePage>) {
        val max = AiContract.limits.notesPagesMax
        var over = false
        change { s ->
            val room = (max - s.pages.size).coerceAtLeast(0)
            over = pages.size > room
            s.copy(pages = s.pages + pages.take(room), reviewing = false)
        }
        if (over) ui { it.copy(problems = it.problems + UiText.Res(Res.string.notes_too_many_pages, max)) }
    }

    /** Typed or pasted text as one page. */
    fun addText(text: String) {
        if (text.isBlank()) return
        // No name: the sheet calls it typed text in the reader's language.
        addPages(listOf(newPage(PageKind.TEXT, "", SourcePage(PageKind.TEXT, text.trim()))))
    }

    /** Picked, shared or scanned files (content addresses or paths), in order, each as one page or more. */
    fun addFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch {
            val sources = uris.map { uri ->
                val room = (AiContract.limits.notesPagesMax - (_state.value?.session?.pages?.size ?: 0)).coerceAtLeast(1)
                files.open(uri, room).also { addSource(it) }
            }
            // The first file with a date dates the notes, while they are new and still dated today.
            val date = sources.firstNotNullOfOrNull { it.date }
            if (date != null) change { s -> if (s.date == today().toString() && s.plan == null) s.copy(date = date.toString()) else s }
        }
    }

    private fun addSource(source: NotesSource) {
        val problem = when (source.problem) {
            null -> null
            NotesFileProblem.TYPE -> UiText.Res(Res.string.notes_file_type, source.name)
            NotesFileProblem.SIZE -> UiText.Res(Res.string.notes_file_size, source.name, AiContract.limits.notesFileMaxMb)
            NotesFileProblem.UNREADABLE -> UiText.Res(Res.string.notes_file_unreadable, source.name)
            NotesFileProblem.PASSWORD -> UiText.Res(Res.string.notes_file_password, source.name)
            NotesFileProblem.IMAGE -> UiText.Res(Res.string.notes_file_image, source.name)
        }
        if (problem != null) ui { it.copy(problems = it.problems + problem) }
        if (source.morePages) ui { it.copy(problems = it.problems + UiText.Res(Res.string.notes_more_pdf_pages, source.name)) }
        addPages(source.pages.map { newPage(it.kind, source.name, it) })
    }

    fun dismissProblems() = ui { it.copy(problems = emptyList()) }

    fun movePage(id: String, by: Int) = change { s ->
        val from = s.pages.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in s.pages.indices) return@change s
        val list = s.pages.toMutableList()
        list.add(to, list.removeAt(from))
        s.copy(pages = list)
    }

    fun removePage(id: String) {
        change { s -> s.copy(pages = s.pages.filterNot { it.id == id }) }
        sweepFiles()
    }

    /** The transcript of a page as the person corrected it. */
    fun setPageText(id: String, text: String) = change { s ->
        s.copy(pages = s.pages.map { if (it.id == id) it.copy(text = text, state = if (it.kind.isImage && text.isNotBlank()) PageState.READ else it.state) else it })
    }

    /** "Read as image": a PDF page whose text layer is junk is drawn and read as a picture. */
    fun readAsImage(id: String) {
        val page = _state.value?.session?.pages?.firstOrNull { it.id == id } ?: return
        val pdf = page.pdfPath ?: return
        val index = page.pdfIndex ?: return
        scope.launch {
            val path = page.imagePath ?: files.drawPdfPage(pdf, index)
            if (path == null) {
                ui { it.copy(problems = it.problems + UiText.Res(Res.string.notes_file_image, page.name)) }
                return@launch
            }
            change { s -> s.copy(pages = s.pages.map { if (it.id == id) it.copy(kind = PageKind.PDF_IMAGE, imagePath = path, text = "", state = PageState.UNREAD) else it }) }
        }
    }

    /** Back to a PDF page's own text. */
    fun useTextLayer(id: String) = change { s ->
        s.copy(pages = s.pages.map { if (it.id == id && it.textLayer != null) it.copy(kind = PageKind.PDF_TEXT, text = it.textLayer, state = PageState.READ, error = null) else it })
    }

    private fun sweepFiles() {
        scope.launch {
            val keep = _state.value?.session?.pages?.flatMap { listOfNotNull(it.imagePath, it.pdfPath) }?.toSet() ?: emptySet()
            files.clear(keep)
        }
    }

    // --- Reading (4.9.1, 4.9.2) -------------------------------------------------------------------

    /** The model that reads the pictures, once known. */
    suspend fun readingModel(): String? = settings.current().readingModel().model

    /** Reads every picture not read yet ([only] one page: its Retry). */
    fun read(only: String? = null) {
        if (readJob?.isActive == true) return
        readJob = scope.launch {
            val s = settings.current()
            if (!s.dictationReady) {
                messages.post(UiText.Res(Res.string.notes_not_ready))
                return@launch
            }
            if (!s.hasImagesConsent()) {
                ui { it.copy(askImages = true) }
                return@launch
            }
            val model = resolveReader() ?: return@launch
            val session = _state.value?.session ?: return@launch
            val title = session.title
            val total = session.pages.size
            val todo = session.pages.withIndex().filter { (_, p) -> p.needsReading && (only == null || p.id == only) }
            if (todo.isEmpty()) return@launch
            change { st -> st.copy(pages = st.pages.map { p -> if (todo.any { it.value.id == p.id }) p.copy(state = PageState.READING, error = null) else p }) }
            val pool = Semaphore(AiContract.limits.notesReadParallel)
            todo.map { (index, page) ->
                async {
                    pool.withPermit { readOne(model, page, index + 1, total, title) }
                }
            }.awaitAll()
        }
    }

    private suspend fun readOne(model: String, page: NotesSourcePage, n: Int, total: Int, title: String) {
        val outcome = try {
            val bytes = page.imagePath?.let { files.readImage(it) } ?: throw LlmException(LlmException.Kind.BAD_ANSWER, "The picture is gone.")
            Result.success(service.readPage(model, bytes, n, total, title))
        } catch (e: CancellationException) {
            change { s -> s.copy(pages = s.pages.map { if (it.id == page.id && it.state == PageState.READING) it.copy(state = PageState.UNREAD) else it }) }
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        change { s ->
            s.copy(
                pages = s.pages.map {
                    if (it.id != page.id) it
                    else outcome.fold(
                        { text -> it.copy(text = text, state = PageState.READ, error = null) },
                        { e -> it.copy(state = PageState.FAILED, error = e.message ?: e.toString()) },
                    )
                },
            )
        }
    }

    /**
     * The reading model of 4.9.1; models never asked whether they read images are asked first. Null
     * (and the "no reader" note) when none does.
     */
    private suspend fun resolveReader(): String? {
        var reading = settings.current().readingModel()
        for (model in reading.untested) {
            if (reading.model != null) break
            ui { it.copy(checkingReader = model) }
            runCatching { providers.testVision(model) }
            reading = settings.current().readingModel()
        }
        ui { it.copy(checkingReader = null, noReader = reading.model == null) }
        return reading.model
    }

    fun dismissNoReader() = ui { it.copy(noReader = false) }

    /** The images agreement (section 7.1): agreed, the reading starts. */
    fun answerImages(agreed: Boolean) {
        ui { it.copy(askImages = false) }
        if (!agreed) return
        scope.launch {
            settings.update { it.copy(consented = it.consented + it.imagesConsentKey()) }
            read()
        }
    }

    // --- Organizing (4.9.3 to 4.9.7) ----------------------------------------------------------

    private fun transcript(session: NotesSession): List<NotesPage> =
        session.pages.mapIndexed { i, p -> NotesPage(i + 1, p.text) }

    /** Organize, or Retry from the batch that failed. */
    fun organize() {
        val session = _state.value?.session ?: return
        if (organizeJob?.isActive == true) return
        if (session.pages.any { it.needsReading }) {
            messages.post(UiText.Res(Res.string.notes_read_first))
            return
        }
        val batches = NotesRules.batches(transcript(session))
        if (batches.isEmpty()) return
        val resume = session.organizeError != null && session.plan != null && session.batches == batches.size
        change { s ->
            if (resume) s.copy(organizing = true, organizeError = null)
            else s.copy(organizing = true, organizeError = null, plan = NotesPlan(), batches = batches.size, batchesDone = 0, checkedDrafts = 0, choices = emptyMap())
        }
        organizeJob = scope.launch {
            try {
                val start = _state.value?.session ?: return@launch
                val context = service.context(TaskDefaults(start.projectId, null, start.listName), LocalDate.parse(start.date))
                val people = service.people()
                ui { it.copy(context = context) }
                for (i in start.batchesDone until batches.size) {
                    val s = _state.value?.session ?: return@launch
                    val plan = s.plan ?: NotesPlan()
                    val batch = try {
                        service.organize(
                            context, people, s.title, batches[i],
                            known = service.known(plan.projects, context),
                            firstProjectNumber = plan.projects.size + 1,
                            firstDraftNumber = s.checkedDrafts + 1,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        change { it.copy(organizing = false, organizeError = e.message ?: e.toString()) }
                        return@launch
                    }
                    change { it.copy(plan = NotesRules.merge(plan, batch), batchesDone = i + 1, checkedDrafts = it.checkedDrafts + batch.drafts.size) }
                }
                change { it.copy(organizing = false, reviewing = true) }
                val done = _state.value?.session?.plan
                if (done == null || (done.drafts.isEmpty() && done.decisions.isEmpty() && done.questions.isEmpty() && done.projects.isEmpty())) {
                    messages.post(UiText.Res(Res.string.notes_nothing))
                    change { it.copy(reviewing = false) }
                }
            } catch (e: CancellationException) {
                change { it.copy(organizing = false) }
                throw e
            }
        }
    }

    /** Stops organizing; the plan so far stays. */
    fun cancelOrganize() {
        organizeJob?.cancel()
        change { it.copy(organizing = false) }
    }

    /** The plan so far after a batch failed. */
    fun reviewSoFar() = change { it.copy(reviewing = true) }

    fun backToPages() = change { it.copy(reviewing = false) }

    /** Update (4.9.8): the words as an edit of a one-batch plan. */
    fun update(words: String) {
        val ui = _state.value ?: return
        val session = ui.session
        val plan = session.plan ?: return
        if (words.isBlank() || ui.updating || !NotesRules.canUpdate(session.batches, plan.drafts.size)) return
        ui { it.copy(updating = true) }
        scope.launch {
            try {
                val context = ui.context ?: service.context(TaskDefaults(session.projectId, null, session.listName), LocalDate.parse(session.date))
                val pages = NotesRules.batches(transcript(session)).flatten()
                val result = service.organize(
                    context, service.people(), session.title, pages,
                    known = emptyList(), firstProjectNumber = 1, firstDraftNumber = 1,
                    plan = NotesRules.planJson(plan.projects, plan.drafts, context),
                    words = words.trim(),
                )
                change { it.copy(plan = result, checkedDrafts = result.drafts.size) }
                ui { it.copy(updating = false, context = context) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui { it.copy(updating = false) }
                messages.post((e as? LlmException)?.message ?: e.toString())
            }
        }
    }

    // --- Review (4.9.9) ---------------------------------------------------------------------------

    fun setFilter(filter: NotesFilter) = change { s ->
        // A filter sets every card's tick again; the owners chosen stay.
        s.copy(filter = filter, choices = s.choices.mapValues { (_, c) -> c.copy(ticked = null) })
    }

    fun setAgreed(on: Boolean) = change { it.copy(agreed = on) }

    fun setToLog(on: Boolean) = change { it.copy(toLog = on) }

    fun setChoice(ref: String, choice: NotesChoice) = change { s -> s.copy(choices = s.choices + (ref to choice)) }

    private fun changePlan(edit: (NotesPlan) -> NotesPlan) = change { s -> s.copy(plan = s.plan?.let(edit)) }

    fun updateDraft(ref: String, draft: CheckedDraft) = changePlan { p -> p.copy(drafts = p.drafts.map { if (it.ref == ref) it.copy(draft = draft) else it }) }

    fun removeDraft(ref: String) = changePlan { p -> p.copy(drafts = p.drafts.filterNot { it.ref == ref }) }

    fun renameProject(ref: String, title: String) = changePlan { p ->
        p.copy(projects = p.projects.map { if (it.ref == ref) it.copy(title = DictationChecks.oneLine(title)) else it })
    }

    fun removeProject(ref: String) {
        val defaultProject = _state.value?.context?.defaultProjectId ?: 0L
        changePlan { p ->
            val (projects, drafts) = BrainDumpChecks.removeProject(ref, p.projects, p.drafts.map { it.draft }, defaultProject)
            val byRef = drafts.associateBy { it.ref }
            p.copy(projects = projects, drafts = p.drafts.map { d -> byRef[d.ref]?.let { d.copy(draft = it) } ?: d })
        }
    }

    // --- Adding (4.9.10) ------------------------------------------------------------------------

    /** One card as it is added: its title by owner, the person's label, the source line in its notes. */
    private fun asAdded(card: NotesDraft, action: OwnerAction, session: NotesSession, world: NotesOwnerWorld): CheckedDraft {
        var title = card.draft.title
        var labels = card.draft.labels
        if (action == OwnerAction.Name && card.owner != null && card.owner != "me") title = NotesRules.namedTitle(card.owner, title)
        val personId = (action as? OwnerAction.Confirm)?.personId ?: (action as? OwnerAction.Promise)?.personId
        world.person(personId ?: -1)?.let { person ->
            if (action is OwnerAction.Confirm) title = Promises.confirmTitle(person.title, title)
            if (labels.none { it.id == person.id }) labels = labels + DraftLabel(person.id, person.title)
        }
        val line = NotesRules.sourceLine(session.title, session.date, card.page, card.from)
        return card.draft.copy(title = title, labels = labels, notes = NotesRules.withSource(card.draft.notes, line))
    }

    fun add() {
        val current = _state.value ?: return
        if (current.adding) return
        val session = current.session
        val plan = session.plan ?: return
        val world = owners.value
        val picked = plan.drafts.map { d -> Triple(d, world.choice(d, session).first, world.choice(d, session).second) }.filter { it.third }
        if (picked.isEmpty()) {
            messages.post(UiText.Res(Res.string.notes_plan_nothing_ticked))
            return
        }
        val drafts = picked.map { (card, action, _) -> asAdded(card, action, session, world) }
        val projects = NotesRules.projectsInUse(plan.projects, drafts.map { it.projectRef })
        if (projects.any { it.title.isBlank() }) return
        ui { it.copy(adding = true) }
        scope.launch {
            val added = planWriter.add(CheckResult(drafts, emptyList(), emptyList(), projects), dayClock.day.value.zone)
            if (added.error != null) {
                ui { it.copy(adding = false) }
                messages.post(added.error)
                return@launch
            }
            // Who the tasks are for: assignees (queued offline) and Pro promises, once the tasks have ids.
            val ledgerBefore = mutableListOf<Triple<String, String, JsonObject?>>()
            val ids = added.drafts?.byDraft.orEmpty()
            val collab = world.collab
            for ((i, entry) in picked.withIndex()) {
                val id = ids.getOrNull(i) ?: continue
                when (val action = entry.second) {
                    is OwnerAction.Assign -> {
                        val user = collab.projects.values.asSequence().flatMap { it.members }.firstOrNull { it.id == action.userId }
                        val task = tasks.getByIds(setOf(id)).firstOrNull()
                        val ok = user != null && task != null && collaboration.setAssigned(task, user, true) !is com.rendyhd.vicu.util.NetworkResult.Error
                        if (!ok) messages.post(UiText.Res(Res.string.notes_plan_assign_failed, drafts[i].title))
                    }
                    is OwnerAction.Promise -> proLedger?.let { ledger ->
                        ledgerBefore += Triple("promises", id.toString(), ledger.current().value("promises", id))
                        ledger.setRecord("promises", id.toString(), JsonObject(mapOf("dir" to JsonPrimitive("theirs"), "person" to JsonPrimitive(action.personId))))
                    }
                    else -> Unit
                }
            }
            if (session.toLog && world.people.isNotEmpty() && proLedger != null) {
                for (decision in plan.decisions) {
                    val id = randomUuid()
                    val by = decision.by?.let { name -> world.people.firstOrNull { People.normalizeName(it.title) == People.normalizeName(name) }?.id }
                    ledgerBefore += Triple("log", id, null)
                    proLedger.setRecord(
                        "log",
                        id,
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive("decision"),
                                "text" to JsonPrimitive(decision.what),
                                "by" to (by?.let { JsonPrimitive(it) } ?: JsonNull),
                                "at" to JsonPrimitive(session.date),
                                "initiative" to JsonNull,
                            ),
                        ),
                    )
                }
            }
            _state.value = null
            store.save(null)
            files.clear()
            val n = added.drafts?.taskIds?.size ?: 0
            messages.post(UiText.Plural(Res.plurals.notes_added, n), UiText.Res(Res.string.notes_undo)) {
                scope.launch {
                    planWriter.undo(added)
                    for ((collection, id, value) in ledgerBefore.asReversed()) proLedger?.setRecord(collection, id, value)
                }
            }
        }
    }
}
