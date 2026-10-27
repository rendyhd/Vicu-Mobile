package com.rendyhd.vicu.ui.screens.guided

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendyhd.vicu.ai.AiSettingsStore
import com.rendyhd.vicu.data.repository.WeeklyReviewPreferences
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.Guided
import com.rendyhd.vicu.util.WeeklyStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A step of the weekly review and whether it is done. */
data class WeeklyStepUi(val step: WeeklyStep, val done: Boolean)

data class WeeklyReviewUiState(
    val loading: Boolean = true,
    val steps: List<WeeklyStepUi> = emptyList(),
    /** The tasks of `done` (Look back) and `upcoming` (Look ahead). */
    val recentlyDone: List<Task> = emptyList(),
    val lookAhead: List<Task> = emptyList(),
    /** "Last done 9 days ago", or "Not done yet". */
    val lastDone: String = "",
    val dictationReady: Boolean = false,
    val finished: Boolean = false,
) {
    val next: WeeklyStepUi? get() = steps.firstOrNull { !it.done }
}

/**
 * The weekly review (docs/cross-app-semantics-v1.md, section 14.7): its steps in GTD order, each
 * done when its session ends, when it is looked at, or when the person marks it; Finish records the
 * day as `weekly_review.last`, on every device.
 */
class WeeklyReviewViewModel(
    private val source: GuidedWorldSource,
    private val weekly: WeeklyReviewPreferences,
    private val progress: GuidedProgress,
    private val aiSettings: AiSettingsStore,
    private val messages: AppMessages,
    private val dayClock: DayClock,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WeeklyReviewUiState())
    val uiState: StateFlow<WeeklyReviewUiState> = _uiState.asStateFlow()

    /** Steps the person marked done or opened (Anything on your mind?, Look back, Look ahead). */
    private val marked = MutableStateFlow<Set<String>>(emptySet())

    init {
        progress.reset()
        viewModelScope.launch {
            combine(source.world, progress.finished, marked, weekly.last, aiSettings.settings) { w, finished, marked, last, ai ->
                val steps = Guided.weeklySteps(w).map { step ->
                    val empty = step.count == 0 && step.id != Guided.STEP_MIND
                    WeeklyStepUi(step, empty || step.id in finished || step.id in marked)
                }
                val age = Guided.weeklyReviewAge(last, w.today)
                WeeklyReviewUiState(
                    loading = false,
                    steps = steps,
                    recentlyDone = Guided.recentlyDone(w),
                    lookAhead = Guided.lookAhead(w),
                    lastDone = if (age == null) "Not done yet" else "Last done ${Guided.ago(age)}",
                    dictationReady = ai.dictationReady,
                )
            }.collect { next -> _uiState.update { next.copy(finished = it.finished) } }
        }
    }

    /** The step is done: the person said so, or opened one that is only looked at. */
    fun markDone(id: String) = marked.update { it + id }

    /** Finish: today becomes the last weekly review (section 14.7). */
    fun finish() {
        viewModelScope.launch {
            weekly.finish(dayClock.day.value.date)
            _uiState.update { it.copy(finished = true) }
            messages.post("Weekly review done")
        }
    }
}
