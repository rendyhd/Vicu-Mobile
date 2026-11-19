package com.rendyhd.vicu.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rendyhd.vicu.notification.TodayCountNotification
import com.rendyhd.vicu.util.DayClock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Rebuilds the task list widgets' state ([TaskWidgetStates], shared with iOS) and redraws them.
 * Every refresh also brings the count on the app icon up to date ([TodayCountNotification]),
 * whether or not there are widgets.
 */
class TaskWidgetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val states: TaskWidgetStates,
    private val dayClock: DayClock,
    private val todayCount: TodayCountNotification,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TaskWidgetWorker"
    }

    override suspend fun doWork(): Result {
        // On its own: a failure here must not keep the widgets from updating, nor the other way round.
        try {
            todayCount.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "App icon count not updated", e)
        }
        return try {
            val manager = GlanceAppWidgetManager(applicationContext)
            val glanceIds = manager.getGlanceIds(TaskListWidget::class.java)

            if (glanceIds.isEmpty()) {
                Log.d(TAG, "No widget instances found")
                return Result.success()
            }

            // Auth guard: if not logged in, show message instead of stale data
            if (!states.isSignedIn()) {
                Log.d(TAG, "User not logged in, showing login prompt on all widgets")
                for (glanceId in glanceIds) {
                    val appWidgetId = manager.getAppWidgetId(glanceId)
                    val config = WidgetConfigStore.getConfig(applicationContext, appWidgetId)
                    val state = states.signedOut()
                        .copy(transparentBackground = config?.transparentBackground ?: false)
                    write(glanceId, state)
                    TaskListWidget().update(applicationContext, glanceId)
                }
                return Result.success()
            }

            val singleWidgetId = inputData.getInt("app_widget_id", -1)
            val updateAll = inputData.getBoolean("update_all", false)

            // The day is re-read first: this runs from a worker that may wake long after the
            // clock last ticked.
            dayClock.refresh()
            val batch = states.prepare(dayClock.day.value)

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
                write(glanceId, state)
                TaskListWidget().update(applicationContext, glanceId)
                Log.d(TAG, "Widget $appWidgetId updated with ${state.tasks.size} of ${state.totalCount} tasks")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.retry()
        }
    }

    private suspend fun write(glanceId: androidx.glance.GlanceId, state: TaskWidgetState) {
        updateAppWidgetState(
            applicationContext,
            TaskWidgetStateDefinition,
            glanceId,
        ) { prefs ->
            prefs.toMutablePreferences().apply {
                this[TaskWidgetStateDefinition.KEY_STATE] = TaskWidgetStateDefinition.encodeState(state)
            }
        }
    }
}
