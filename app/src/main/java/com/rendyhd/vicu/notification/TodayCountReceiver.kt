package com.rendyhd.vicu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rendyhd.vicu.widget.WidgetUpdateScheduler

/**
 * The two broadcasts of [TodayCountNotification]: the local midnight, when the count of the new
 * day is worked out with a widget refresh (which also rolls the widgets over), and the user swiping
 * the notification away, remembered so the same number does not come back.
 */
class TodayCountReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MIDNIGHT -> WidgetUpdateScheduler.enqueueImmediateUpdateAll(context)
            ACTION_DISMISSED -> {
                val count = intent.getIntExtra(EXTRA_COUNT, -1)
                if (count > 0) TodayCountNotification.rememberDismissed(context, count)
            }
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.rendyhd.vicu.TODAY_COUNT_MIDNIGHT"
        const val ACTION_DISMISSED = "com.rendyhd.vicu.TODAY_COUNT_DISMISSED"
        const val EXTRA_COUNT = "count"
    }
}
