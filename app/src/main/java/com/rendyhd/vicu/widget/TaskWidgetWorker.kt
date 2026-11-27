package com.rendyhd.vicu.widget

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.util.DayClock

/** Rebuilds the task list widgets' state ([TaskWidgetStates], shared with iOS) and redraws them. */
class TaskWidgetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val states: TaskWidgetStates,
    private val dayClock: DayClock,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TaskWidgetWorker"
        private val renderContextKey = stringPreferencesKey("task_widget_render_context")
    }

    override suspend fun doWork(): Result {
        return try {
            val manager = GlanceAppWidgetManager(applicationContext)
            val glanceIds = manager.getGlanceIds(TaskListWidget::class.java)

            if (glanceIds.isEmpty()) {
                Log.d(TAG, "No widget instances found")
                return Result.success()
            }

            dayClock.refresh()
            val day = dayClock.day.value
            val renderContext = "${day.date}|${day.zone.id}|${DateFormat.is24HourFormat(applicationContext)}"
            val forceRedraw = runAttemptCount > 0 || inputData.getBoolean("force_redraw", false)

            // Auth guard: if not logged in, show message instead of stale data
            if (!states.isSignedIn()) {
                Log.d(TAG, "User not logged in, showing login prompt on all widgets")
                for (glanceId in glanceIds) {
                    val appWidgetId = manager.getAppWidgetId(glanceId)
                    val config = WidgetConfigStore.getConfig(applicationContext, appWidgetId)
                    val state = states.signedOut()
                        .copy(transparentBackground = config?.transparentBackground ?: false)
                    renderIfChanged(glanceId, state, renderContext, forceRedraw)
                }
                return Result.success()
            }

            val singleWidgetId = inputData.getInt("app_widget_id", -1)
            val updateAll = inputData.getBoolean("update_all", false)

            val batch = states.prepare(day)

            Log.d(TAG, "doWork: glanceIds=${glanceIds.size}, singleWidgetId=$singleWidgetId, updateAll=$updateAll")

            for (glanceId in glanceIds) {
                val appWidgetId = manager.getAppWidgetId(glanceId)

                if (!updateAll && singleWidgetId != -1 && appWidgetId != singleWidgetId) {
                    Log.d(TAG, "Skipping widget $appWidgetId (target=$singleWidgetId)")
                    continue
                }

                val config = WidgetConfigStore.getConfig(applicationContext, appWidgetId)
                Log.d(TAG, "Widget $appWidgetId config=$config (null means default TODAY)")
                val state = batch.build(config ?: WidgetConfig())
                    .copy(transparentBackground = config?.transparentBackground ?: false)
                renderIfChanged(glanceId, state, renderContext, forceRedraw)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.retry()
        }
    }

    private suspend fun renderIfChanged(
        glanceId: GlanceId,
        state: TaskWidgetState,
        renderContext: String,
        forceRedraw: Boolean,
    ) {
        var redraw = false
        updateAppWidgetState(
            applicationContext,
            TaskWidgetStateDefinition,
            glanceId,
        ) { prefs ->
            val previous = TaskWidgetStateDefinition.parseState(prefs)
            redraw = forceRedraw || prefs[renderContextKey] != renderContext || state.needsRedrawFrom(previous)
            if (!redraw) return@updateAppWidgetState prefs

            prefs.toMutablePreferences().apply {
                this[renderContextKey] = renderContext
                this[TaskWidgetStateDefinition.KEY_STATE] = TaskWidgetStateDefinition.encodeState(state)
            }
        }
        if (redraw) TaskListWidget().update(applicationContext, glanceId)
    }
}
