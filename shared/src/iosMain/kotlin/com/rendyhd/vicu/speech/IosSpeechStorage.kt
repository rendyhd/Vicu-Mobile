package com.rendyhd.vicu.speech

import com.rendyhd.vicu.util.IosPaths
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Speech files in Application Support, kept out of backups: downloaded models in `speech-models/`.
 * The bundled voice detector is read where it is, in the app bundle (iosApp/project.yml copies
 * `native/speech/models/ggml-silero-v6.2.0.bin` in). Nothing is touched until a function is called
 * (CLAUDE.md, gotcha 19); the network watcher starts on the first [isUnmeteredNetwork].
 */
class IosSpeechStorage : SpeechStorage {
    private val lock = NSLock()
    private var monitor: nw_path_monitor_t = null
    private var unmetered = false

    override fun modelsDirectory(): String = IosPaths.excludedSubdirectory(IosPaths.applicationSupport, MODELS_FOLDER)

    override suspend fun vadModelPath(): String? =
        NSBundle.mainBundle.pathForResource(VAD_NAME, ofType = VAD_TYPE)
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    override fun totalMemoryBytes(): Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    /** Wi-Fi or wired and not in Low Data Mode: a path that is neither expensive nor constrained. */
    override fun isUnmeteredNetwork(): Boolean {
        if (locked { monitor == null }) startMonitor()
        return locked { unmetered }
    }

    override fun freeBytes(): Long {
        val directory = modelsDirectory()
        val values = NSURL.fileURLWithPath(directory)
            .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error = null)
        (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue?.let { if (it > 0) return it }
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(directory, error = null)
        return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
    }

    /** Starts watching and waits a moment for the first answer, which comes almost at once. */
    private fun startMonitor() {
        val firstAnswer = dispatch_semaphore_create(0)
        var answered = false
        val created = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(created) { path ->
            val free = nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path) && !nw_path_is_constrained(path)
            locked { unmetered = free }
            if (!answered) {
                answered = true
                dispatch_semaphore_signal(firstAnswer)
            }
        }
        nw_path_monitor_set_queue(created, dispatch_queue_create("com.rendyhd.vicu.speech.network", null))
        val mine = locked { if (monitor == null) monitor = created; monitor === created }
        if (!mine) return
        nw_path_monitor_start(created)
        dispatch_semaphore_wait(firstAnswer, dispatch_time(DISPATCH_TIME_NOW, FIRST_ANSWER_NANOS))
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val MODELS_FOLDER = "speech-models"

        /** The bundled Silero model (`speech.vad.model.file` in test-fixtures/ai-tools-v1.json). */
        const val VAD_NAME = "ggml-silero-v6.2.0"
        const val VAD_TYPE = "bin"
        const val FIRST_ANSWER_NANOS = 1_000_000_000L
    }
}
