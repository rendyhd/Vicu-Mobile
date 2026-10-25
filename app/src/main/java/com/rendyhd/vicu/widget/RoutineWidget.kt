package com.rendyhd.vicu.widget

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import android.text.format.DateFormat
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.putViewTarget
import com.rendyhd.vicu.ui.navigation.ViewTarget
import com.rendyhd.vicu.R
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import com.rendyhd.vicu.ui.theme.routineMarkColor
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.DayClock
import com.rendyhd.vicu.util.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext
import java.util.UUID

class RoutineWidget : GlanceAppWidget() {
    companion object {
        private val COMPACT = DpSize(160.dp, 64.dp)
        private val LARGE = DpSize(250.dp, 180.dp)

        /**
         * Held by a refresh while it works out and writes the state, and by a tap while it queues
         * its tick and hides the row, so a refresh never reads the pending ticks before a tap queues
         * one and then writes over that tap's hidden row.
         */
        internal val stateLock = Mutex()
    }

    override val stateDefinition = RoutineWidgetStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val schemes = loadWidgetSchemes(context)
        val colors = schemes.toColorProviders()
        val rings = RoutineRingColors(schemes)
        provideContent {
            val prefs = currentState<Preferences>()
            val state = RoutineWidgetStateDefinition.parseState(prefs)
            GlanceTheme(colors = colors) {
                if (androidx.glance.LocalSize.current.height < 120.dp) {
                    CompactRoutineWidget(state, rings)
                } else {
                    LargeRoutineWidget(state, rings)
                }
            }
        }
    }

    /**
     * Shows today's routines on every routine widget, with the ticks still being saved laid over
     * the database (see [RoutineWidgetPendingTicks]). A tick's worker that has just finished with it
     * leaves it out as [ignoringWork], or as [ignoringRoutine] when it was refused.
     */
    suspend fun updateAllWidgets(
        context: Context,
        ignoringWork: UUID? = null,
        ignoringRoutine: String? = null,
    ) {
        val repository = GlobalContext.get().get<RoutineRepository>()
        val time = GlobalContext.get().get<TimeSource>()
        // The day is re-read first: this can run long after the clock last ticked.
        val dayClock = GlobalContext.get().get<DayClock>()
        dayClock.refresh()
        val today = dayClock.day.value.date.toString()
        val format = DateDisplayFormat.system(DateFormat.is24HourFormat(context))
        val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(RoutineWidget::class.java)
        stateLock.withLock {
            // The database first: a tick missing from it is still pending, because its worker
            // refreshes (and so waits for this lock) before its work ends.
            val saved = RoutineWidgetState.from(repository.observeDay(today).first(), format)
            val pending = RoutineWidgetPendingTicks.read(context, ignoringWork, ignoringRoutine)
            val state = RoutineWidgetPendingTicks.applyTo(saved, pending, time.now().toEpochMilliseconds())
            glanceIds.forEach { glanceId -> updateState(context, glanceId, state) }
        }
        glanceIds.forEach { glanceId -> update(context, glanceId) }
    }

    suspend fun updateState(
        context: Context,
        glanceId: GlanceId,
        state: RoutineWidgetState,
    ) {
        updateAppWidgetState(context, RoutineWidgetStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[RoutineWidgetStateDefinition.KEY_STATE] =
                    RoutineWidgetStateDefinition.encodeState(state)
            }
        }
    }
}

class OpenRoutinesWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putViewTarget(ViewTarget.Routines)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }
}

class ToggleRoutineWidgetAction : ActionCallback {
    companion object {
        val RoutineIdKey = ActionParameters.Key<String>("routine_id")
        val DateKey = ActionParameters.Key<String>("date")
        val SlotIdKey = ActionParameters.Key<String>("slot_id")
        val CompletedKey = ActionParameters.Key<Boolean>("completed")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val routineId = parameters[RoutineIdKey] ?: return
        val date = parameters[DateKey] ?: return
        val slotId = parameters[SlotIdKey] ?: return
        val status = if (parameters[CompletedKey] == true) OccurrenceStatus.PENDING else OccurrenceStatus.COMPLETED

        val widget = RoutineWidget()
        val tick = RoutineWidgetPendingTicks.Tick(
            routineId = routineId,
            date = date,
            slotId = slotId,
            status = status,
            at = GlobalContext.get().get<TimeSource>().now().toEpochMilliseconds(),
        )
        RoutineWidget.stateLock.withLock {
            // Queued before the row hides, so every refresh from here on finds the tick pending.
            RoutineWidgetActionScheduler.enqueue(context, tick)
            updateAppWidgetState(context, RoutineWidgetStateDefinition, glanceId) { prefs ->
                val current = RoutineWidgetStateDefinition.parseState(prefs)
                val updated = current.withStatus(routineId, date, slotId, status)
                prefs.toMutablePreferences().apply {
                    this[RoutineWidgetStateDefinition.KEY_STATE] =
                        RoutineWidgetStateDefinition.encodeState(updated)
                }
            }
        }
        widget.update(context, glanceId)
    }
}

/**
 * The check ring of a routine in its own colour, day and night, moved only as far as it takes to
 * read on the widget. Glance draws the widget background from secondaryContainer with its tone
 * moved 5 lighter (light) or 10 darker (dark), which lies between surface and secondaryContainer
 * in both themes, so reading on those two covers it.
 */
private class RoutineRingColors(private val schemes: WidgetSchemes) {
    fun of(hex: String): ColorProvider? {
        val day = schemes.light.let { routineMarkColor(hex, it.onSurface, listOf(it.surface, it.secondaryContainer)) }
        val night = schemes.dark.let { routineMarkColor(hex, it.onSurface, listOf(it.surface, it.secondaryContainer)) }
        return if (day != null && night != null) DayNightColorProvider(day = day, night = night) else null
    }
}

@Composable
private fun CompactRoutineWidget(state: RoutineWidgetState, rings: RoutineRingColors) {
    val next = state.visibleOccurrences.firstOrNull { it.status == OccurrenceStatus.PENDING }
        ?: state.visibleOccurrences.firstOrNull()
    Row(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(16.dp)
            .clickable(actionRunCallback<OpenRoutinesWidgetAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "Routines  ${state.completedCount}/${state.scheduledCount}",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                maxLines = 1,
            )
            Text(
                text = when {
                    state.isAllDone -> "All done for today"
                    next != null -> next.routineName
                    else -> "Nothing scheduled"
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
        if (next != null) RoutineWidgetCheckbox(next, rings)
    }
}

@Composable
private fun LargeRoutineWidget(state: RoutineWidgetState, rings: RoutineRingColors) {
    val visibleOccurrences = state.visibleOccurrences
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(16.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionRunCallback<OpenRoutinesWidgetAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Routines",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "${state.completedCount}/${state.scheduledCount}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        when {
            state.occurrences.isEmpty() -> {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing scheduled", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                }
            }

            state.isAllDone -> {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "All done for today",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    )
                }
            }

            else -> {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(
                        visibleOccurrences,
                        itemId = { "${it.key}:${it.status}".hashCode().toLong() },
                    ) { occurrence ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoutineWidgetCheckbox(occurrence, rings)
                            Spacer(GlanceModifier.width(12.dp))
                            Column(modifier = GlanceModifier.defaultWeight()) {
                                Text(
                                    occurrence.routineName,
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                                    maxLines = 1,
                                )
                                Text(
                                    occurrence.slotLabel,
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineWidgetCheckbox(occurrence: RoutineWidgetItem, rings: RoutineRingColors) {
    val completed = occurrence.status == OccurrenceStatus.COMPLETED
    Box(
        modifier = GlanceModifier.size(36.dp).cornerRadius(18.dp).clickable(
            actionRunCallback<ToggleRoutineWidgetAction>(
                actionParametersOf(
                    ToggleRoutineWidgetAction.RoutineIdKey to occurrence.routineId,
                    ToggleRoutineWidgetAction.DateKey to occurrence.scheduledDate,
                    ToggleRoutineWidgetAction.SlotIdKey to occurrence.slotId,
                    ToggleRoutineWidgetAction.CompletedKey to completed,
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (completed) R.drawable.ic_widget_circle_checked else R.drawable.ic_widget_circle_unchecked),
            contentDescription = if (completed) "Undo" else "Complete",
            modifier = GlanceModifier.size(22.dp),
            colorFilter = if (completed) null else ColorFilter.tint(rings.of(occurrence.color) ?: GlanceTheme.colors.outline),
        )
    }
}
