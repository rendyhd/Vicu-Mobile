package com.rendyhd.vicu.widget

import android.content.Context
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object WidgetUpdateScheduler {

    private const val PERIODIC_WORK_NAME = "widget_periodic_refresh"

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<TaskWidgetWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("update_all" to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            // UPDATE (not KEEP) so changes to the refresh cadence/config replace the
            // existing periodic job instead of being ignored until it's cancelled.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueImmediateUpdate(context: Context, appWidgetId: Int) {
        val request = OneTimeWorkRequestBuilder<TaskWidgetWorker>()
            .setInputData(workDataOf("app_widget_id" to appWidgetId))
            .expeditedFromAndroid12()
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun enqueueImmediateUpdateAll(context: Context, forceRedraw: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<TaskWidgetWorker>()
            .setInputData(workDataOf("update_all" to true, "force_redraw" to forceRedraw))
            .expeditedFromAndroid12()
            .build()
        // Unique + REPLACE: rapid mutations collapse into one refresh instead of queueing
        // a redundant worker per repository write.
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (forceRedraw) "widget_appearance_refresh" else "widget_immediate_refresh",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * A widget refresh follows a tap or an edit, so it should not wait for WorkManager's normal
     * scheduling. Only from Android 12: before that an expedited request runs as a foreground
     * service, which needs foreground info this worker does not provide. Out of quota it runs as
     * ordinary work.
     */
    private fun OneTimeWorkRequest.Builder.expeditedFromAndroid12(): OneTimeWorkRequest.Builder = apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
    }
}
