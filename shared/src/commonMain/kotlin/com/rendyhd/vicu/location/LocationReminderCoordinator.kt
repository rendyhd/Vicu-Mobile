package com.rendyhd.vicu.location

import com.rendyhd.vicu.util.ReminderGate
import com.rendyhd.vicu.data.local.NotificationPrefsStore
import com.rendyhd.vicu.data.local.TempIdGenerator
import com.rendyhd.vicu.domain.model.Task
import com.rendyhd.vicu.domain.repository.TaskRepository
import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps the platform's watched regions in step with the location reminders, and turns a region
 * event into notifications. The same on both platforms; [PlatformGeofencer] and [LocationNotifier]
 * are the platform halves.
 *
 * Only reminders of open tasks are watched, and only where this phone knows the place's position.
 * A reminder of a completed task is dropped (on every device, when it is synced), one of a task
 * created offline moves to the server's id once the task is sent, and one whose task is gone is
 * dropped by a [replan] that prunes (after a full sync, when a missing task really is gone).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationReminderCoordinator(
    private val locations: LocationRepository,
    private val taskRepository: TaskRepository,
    private val tempIds: TempIdGenerator,
    private val geofencer: PlatformGeofencer,
    private val notifier: LocationNotifier,
    private val notificationPrefs: NotificationPrefsStore,
    private val scope: CoroutineScope,
    /** Where the device is, to keep the nearest regions when there are more than the platform allows. */
    private val near: suspend () -> GeoPoint? = { null },
    /** Per-project notification modes: a held-back task's place is not watched on this device. */
    private val gate: suspend () -> ReminderGate = { ReminderGate.OPEN },
) {
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    /** What the platform was last told to watch; null until the first plan of this process. */
    private var applied: List<LocationRegion>? = null

    /** Plans again whenever a reminder changes or a task with one is completed, reopened or removed. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (request in requests) {
                delay(DEBOUNCE)
                replan()
            }
        }
        scope.launch {
            locations.reminders
                .flatMapLatest { reminders ->
                    if (reminders.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(reminders.map { reminder -> taskRepository.getById(reminder.taskId).map { reminder.taskId to it?.done } }) { it.toList() }
                    }
                }
                .distinctUntilChanged()
                .collect { requestReplan() }
        }
    }

    fun requestReplan() {
        requests.trySend(Unit)
    }

    /**
     * Brings the watched regions in line with the reminders now. [force] hands them to the platform
     * even when they did not change (after a reboot, or when the platform may have dropped them);
     * [pruneMissing] also drops reminders whose task is not on the device.
     */
    suspend fun replan(force: Boolean = false, pruneMissing: Boolean = false) = mutex.withLock {
        try {
            followTempIds()
            locations.tidy()
            val reminders = locations.allReminders()
            val tasks = taskRepository.getByIds(reminders.mapTo(HashSet()) { it.taskId }).associateBy { it.id }
            val dropped = reminders.filter { reminder ->
                val task = tasks[reminder.taskId]
                if (task == null) pruneMissing else task.done
            }.mapTo(HashSet()) { it.taskId }
            locations.removeReminders(dropped)
            val allowed = gate()
            val open = reminders
                .filter { it.taskId !in dropped && tasks[it.taskId]?.let(allowed::allows) == true }
                .mapNotNull { it.watched() }
            val nearPoint = if (open.size > geofencer.capacity) near() else null
            val regions = LocationRegionPlanner.plan(open, geofencer.capacity, nearPoint)
            if (force || regions != applied) {
                geofencer.apply(regions)
                applied = regions
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Planning location reminders failed: ${e.message}")
        }
    }

    /**
     * The platform saw the device enter ([entered]) or leave region [regionId]: shows the reminders
     * waiting for that and removes them. A task done or gone in the meantime shows nothing.
     */
    suspend fun onRegionEvent(regionId: String, entered: Boolean) {
        val trigger = if (entered) LocationTrigger.ARRIVE else LocationTrigger.LEAVE
        val allowed = gate()
        val fired = mutex.withLock {
            followTempIds()
            val candidates = locations.allReminders().mapNotNull { it.watched() }
                .filter { it.trigger == trigger && LocationRegionPlanner.regionId(it) == regionId }
            // A reminder this device holds back stays for the devices that ring for it.
            val known = taskRepository.getByIds(candidates.mapTo(HashSet()) { it.taskId }).associateBy { it.id }
            val due = candidates.filter { reminder -> known[reminder.taskId]?.let(allowed::allows) ?: true }
            if (due.isEmpty()) return@withLock emptyList()
            locations.removeReminders(due.mapTo(HashSet()) { it.taskId })
            due
        }
        if (fired.isEmpty()) return
        val prefs = notificationPrefs.getPrefs().first()
        val tasks = taskRepository.getByIds(fired.mapTo(HashSet()) { it.taskId }).associateBy { it.id }
        for (reminder in fired) {
            val task = tasks[reminder.taskId] ?: continue
            if (task.done || !prefs.taskRemindersEnabled) continue
            notifier.show(task.id, task.title.ifBlank { "Task Reminder" }, body(reminder), prefs.soundEnabled)
        }
        replan()
    }

    /** Moves reminders of tasks created offline to the ids the server gave them. */
    private suspend fun followTempIds() {
        val temp = locations.allReminders().filter { it.taskId < 0 }
        if (temp.isEmpty()) return
        val present = taskRepository.getByIds(temp.mapTo(HashSet()) { it.taskId }).mapTo(HashSet(), Task::id)
        for (reminder in temp) {
            if (reminder.taskId in present) continue
            val realId = tempIds.realIdFor(reminder.taskId) ?: continue
            locations.followTempId(reminder.taskId, realId)
        }
    }

    companion object {
        private const val TAG = "LocationReminders"
        private val DEBOUNCE = 300.milliseconds

        fun body(reminder: LocationReminder): String = when (reminder.trigger) {
            LocationTrigger.ARRIVE -> "Arriving at ${reminder.placeName}"
            LocationTrigger.LEAVE -> "Leaving ${reminder.placeName}"
        }
    }
}
