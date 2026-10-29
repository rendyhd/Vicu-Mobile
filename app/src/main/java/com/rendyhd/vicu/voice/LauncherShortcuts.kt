package com.rendyhd.vicu.voice

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R

/**
 * The app icon's long-press shortcuts, the same two as the iOS quick actions: "Add task" (quick
 * add) and "Dictate" (smart dictation, listening, when it is set up; otherwise the voice card).
 * Published from code, not `shortcuts.xml`, because the debug build has its own package name and a
 * static shortcut has to name it. The earlier "Add by voice" ([VoiceAddActivity]) is left out; a
 * copy pinned to the home screen keeps working, since the activity stays for the widgets.
 */
object LauncherShortcuts {
    fun publish(context: Context) {
        val addTask = ShortcutInfoCompat.Builder(context, "add_task")
            .setShortLabel(context.getString(R.string.shortcut_add_task))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_add))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_SHOW_TASK_ENTRY, true),
            )
            .build()
        val dictate = ShortcutInfoCompat.Builder(context, "dictate")
            .setShortLabel(context.getString(R.string.shortcut_dictate))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_mic))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_START_DICTATION, true),
            )
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(context, listOf(addTask, dictate))
    }
}
