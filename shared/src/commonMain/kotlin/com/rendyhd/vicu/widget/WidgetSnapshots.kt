package com.rendyhd.vicu.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.rendyhd.vicu.ui.navigation.AppLink
import com.rendyhd.vicu.ui.theme.VicuColors
import com.rendyhd.vicu.ui.theme.VicuDarkColorScheme
import com.rendyhd.vicu.ui.theme.VicuDarkColors
import com.rendyhd.vicu.ui.theme.VicuLightColorScheme
import com.rendyhd.vicu.ui.theme.VicuLightColors
import com.rendyhd.vicu.ui.theme.routineMarkColor
import com.rendyhd.vicu.util.ClockDay
import com.rendyhd.vicu.util.DateContext
import com.rendyhd.vicu.util.DateDisplay
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DueDates
import kotlinx.serialization.Serializable

/*
 * What the iOS widgets read. The widget extension is Swift without the shared code, so the app
 * hands it everything it shows, worked out here: the rows of each view for today and for
 * tomorrow (so the widget is right after midnight before the app has run), with their due labels
 * phrased by DateDisplay, the links of the title and the + button, and the colours.
 */

/** One row of a task list widget. */
@Serializable
data class WidgetSnapshotRow(
    val id: Long,
    val title: String,
    val projectName: String,
    /** The due date as the list rows phrase it; empty without one. */
    val dueLabel: String,
    val overdue: Boolean,
    val priority: Int,
    val done: Boolean,
)

/** A view's rows as they are on one local day. */
@Serializable
data class WidgetSnapshotDay(
    val date: String,
    val rows: List<WidgetSnapshotRow>,
    val totalCount: Int,
    /** Shown instead of the rows (signed out, an archived project). */
    val error: String? = null,
)

/** One view a task list widget can show. */
@Serializable
data class TaskWidgetSnapshot(
    val key: String,
    val name: String,
    /** Where a tap on the title goes; the app's own link when the widget opens just the app. */
    val openUrl: String,
    /** Where the + button goes (quick add, in the view's project when there is one). */
    val addUrl: String,
    /** Today first, then tomorrow. */
    val days: List<WidgetSnapshotDay>,
    /** When the second day takes over (the next local midnight), in epoch milliseconds. */
    val nextDayAtMillis: Long,
)

/** A view the widget configuration offers. */
@Serializable
data class WidgetCatalogEntry(
    val key: String,
    val name: String,
    val kind: String,
    /** The id the widget configuration keeps ([WidgetSnapshots.entityId]). */
    val id: String = key,
)

/** The views to choose from, whether anyone is signed in, and the widgets' colours. */
@Serializable
data class WidgetCatalog(
    val signedIn: Boolean,
    val views: List<WidgetCatalogEntry>,
    val light: WidgetPalette,
    val dark: WidgetPalette,
    /**
     * A tag for the account signed in, made up by the app and kept until that account's data is
     * wiped; empty while signed out. Null in a catalog written before the views carried it.
     */
    val account: String? = null,
    /** The account whose projects and lists the widgets configured before then show. */
    val untaggedAccount: String = "",
)

/** Colours as #RRGGBB, from the Vicu scheme the app uses on iOS. */
@Serializable
data class WidgetPalette(
    val background: String,
    val onSurface: String,
    val onSurfaceVariant: String,
    val primary: String,
    val onPrimary: String,
    val outline: String,
    val overdue: String,
    val done: String,
    /** Priority 1 to 5; low, medium, high, urgent, do now. */
    val priorities: List<String>,
)

/** The routine widget: today's occurrences and tomorrow's. */
@Serializable
data class RoutineWidgetSnapshot(
    val enabled: Boolean,
    val openUrl: String,
    val days: List<RoutineWidgetState>,
    val nextDayAtMillis: Long,
)

/** An action the widget extension took that the app still has to store and send. */
@Serializable
data class WidgetAction(
    val kind: String,
    val taskId: Long = 0,
    val routineId: String = "",
    val date: String = "",
    val slotId: String = "",
    val status: String = "",
) {
    companion object {
        const val COMPLETE_TASK = "completeTask"
        const val ROUTINE_STATUS = "routineStatus"
    }
}

object WidgetSnapshots {
    const val CATALOG_FILE = "catalog.json"
    const val ROUTINES_FILE = "routines.json"
    const val VIEWS_DIR = "views"
    const val ACTIONS_DIR = "actions"

    /** The signed-in account's tag ([WidgetCatalog.account]); the app's own, the extension never reads it. */
    const val ACCOUNT_FILE = "account"

    /** A file-safe key for a view: today, inbox, upcoming, anytime, project-12, list-<id>. */
    fun key(type: WidgetViewType, id: String): String = when (type) {
        WidgetViewType.TODAY -> "today"
        WidgetViewType.INBOX -> "inbox"
        WidgetViewType.UPCOMING -> "upcoming"
        WidgetViewType.ANYTIME -> "anytime"
        WidgetViewType.ALL_TASKS -> "all-tasks"
        WidgetViewType.PROJECT -> "project-$id"
        WidgetViewType.CUSTOM_LIST -> "list-" + id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    }

    /**
     * The id WidgetKit keeps for a widget configured to show a view. A smart list shows whichever
     * account is signed in, so its id is its key. A project or custom list belongs to the account
     * it was chosen under, so its id carries that account's tag (`project-12@4f0c2a9e1b7d`): WidgetKit
     * keeps the configuration across a sign-out, and a widget chosen under another account then
     * says to choose another list instead of showing the new account's project 12.
     */
    fun entityId(type: WidgetViewType, key: String, account: String): String = when (type) {
        WidgetViewType.PROJECT, WidgetViewType.CUSTOM_LIST -> if (account.isEmpty()) key else "$key@$account"
        WidgetViewType.TODAY, WidgetViewType.INBOX, WidgetViewType.UPCOMING, WidgetViewType.ANYTIME,
        WidgetViewType.ALL_TASKS -> key
    }

    /**
     * Whose projects and lists the widgets configured before the ids carried an account show
     * (ids such as `project-12`): the account signed in when this version first wrote the catalog
     * over one written before, carried from catalog to catalog after that. [previous] is the
     * catalog being replaced, null when there is none; [account] the tag of the one being written.
     */
    fun untaggedAccount(previous: WidgetCatalog?, account: String): String = when {
        previous == null -> ""
        previous.account == null -> if (previous.signedIn) account else ""
        else -> previous.untaggedAccount
    }

    /** The smart lists every widget can show, in the order the configuration lists them. */
    val smartLists: List<WidgetConfig> = listOf(
        WidgetConfig(WidgetViewType.TODAY, "", "Today"),
        WidgetConfig(WidgetViewType.INBOX, "", "Inbox"),
        WidgetConfig(WidgetViewType.UPCOMING, "", "Upcoming"),
        WidgetConfig(WidgetViewType.ANYTIME, "", "Anytime"),
    )

    /** [states] of one view on today and tomorrow, as the widget shows them. */
    fun snapshot(
        today: TaskWidgetState,
        tomorrow: TaskWidgetState,
        days: Pair<ClockDay, ClockDay>,
        scheme: String,
        format: DateDisplayFormat,
    ): TaskWidgetSnapshot {
        val target = if (today.contextNav) today.viewType.toViewTarget(today.viewId) else null
        val addProjectId = if (!today.smartAdd) null else when (today.viewType) {
            WidgetViewType.PROJECT -> today.viewId.toLongOrNull()
            WidgetViewType.CUSTOM_LIST -> today.addToProjectId.takeIf { it != 0L }
            else -> null
        }
        return TaskWidgetSnapshot(
            key = key(today.viewType, today.viewId),
            name = today.viewName,
            openUrl = target?.let { AppLink.OpenView(it).toUrl(scheme) } ?: "$scheme://",
            addUrl = AppLink.AddTask(addProjectId).toUrl(scheme),
            days = listOf(day(today, days.first, format), day(tomorrow, days.second, format)),
            nextDayAtMillis = DueDates.startOfDay(days.second.date, days.second.zone).toEpochMilliseconds(),
        )
    }

    private fun day(state: TaskWidgetState, day: ClockDay, format: DateDisplayFormat) = WidgetSnapshotDay(
        date = day.date.toString(),
        rows = state.tasks.map { task ->
            WidgetSnapshotRow(
                id = task.id,
                title = task.title,
                projectName = task.projectName,
                dueLabel = DateDisplay.formatDue(DateContext.ROW, task.dueDate, day.date, day.zone, format),
                overdue = DueDates.bucket(task.dueDate, day.date, day.zone) == DueDates.Bucket.OVERDUE,
                priority = task.priority,
                done = task.done,
            )
        },
        totalCount = state.totalCount,
        error = state.error,
    )

    /** [state] with the check ring of each routine in its own colour, on the widget's light and dark background. */
    fun routineDay(state: RoutineWidgetState): RoutineWidgetState = state.copy(
        occurrences = state.occurrences.map { item ->
            item.copy(
                markLight = routineMark(item.color, VicuLightColorScheme),
                markDark = routineMark(item.color, VicuDarkColorScheme),
            )
        },
    )

    // The widget's background is the scheme's surface (WidgetPalette.background).
    private fun routineMark(color: String, scheme: ColorScheme): String =
        routineMarkColor(color, scheme.onSurface, listOf(scheme.surface))?.let(::hex).orEmpty()

    fun palettes(): Pair<WidgetPalette, WidgetPalette> =
        palette(VicuLightColorScheme, VicuLightColors) to palette(VicuDarkColorScheme, VicuDarkColors)

    private fun palette(scheme: ColorScheme, colors: VicuColors) = WidgetPalette(
        background = hex(scheme.surface),
        onSurface = hex(scheme.onSurface),
        onSurfaceVariant = hex(scheme.onSurfaceVariant),
        primary = hex(scheme.primary),
        onPrimary = hex(scheme.onPrimary),
        outline = hex(scheme.outline),
        overdue = hex(scheme.error),
        done = hex(colors.done.color),
        priorities = (1..5).map { hex(colors.priority(it) ?: colors.priorityLow) },
    )

    private fun hex(color: Color): String = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
}
