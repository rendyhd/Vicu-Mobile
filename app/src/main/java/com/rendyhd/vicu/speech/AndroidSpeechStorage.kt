package com.rendyhd.vicu.speech

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import com.rendyhd.vicu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Speech files in `noBackupFilesDir`, which backups and device transfer never include: downloaded
 * models in `speech-models/`, the bundled voice detector copied out of the APK's assets into
 * `speech-vad/` (whisper.cpp opens models by path), apart from the models so deleting those
 * leaves it. Nothing is touched until a function is called.
 */
class AndroidSpeechStorage(private val context: Context) : SpeechStorage {
    private val vadCopy = Mutex()

    override fun modelsDirectory(): String = folder(MODELS_FOLDER).absolutePath

    override suspend fun vadModelPath(): String? = withContext(Dispatchers.IO) {
        vadCopy.withLock {
            val folder = folder(VAD_FOLDER)
            val target = File(folder, VAD_ASSET)
            if (target.isFile && target.length() > 0) return@withLock target.absolutePath
            val partial = File(folder, "$VAD_ASSET.part")
            try {
                context.assets.open(VAD_ASSET).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                if (!partial.renameTo(target)) throw IOException("could not move the copy into place")
                // A detector of an older version is not used again.
                folder.listFiles()?.filter { it.name != VAD_ASSET }?.forEach { it.delete() }
                target.absolutePath
            } catch (e: IOException) {
                Logger.w(TAG, "No voice detector model: ${e.message}")
                partial.delete()
                null
            }
        }
    }

    override fun totalMemoryBytes(): Long {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    override fun isUnmeteredNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }

    override fun freeBytes(): Long = try {
        StatFs(modelsDirectory()).availableBytes
    } catch (e: IllegalArgumentException) {
        0L
    }

    private fun folder(name: String): File = File(context.noBackupFilesDir, name).apply { mkdirs() }

    private companion object {
        const val TAG = "SpeechStorage"
        const val MODELS_FOLDER = "speech-models"
        const val VAD_FOLDER = "speech-vad"

        /** The bundled Silero model (`speech.vad.model.file` in test-fixtures/ai-tools-v1.json). */
        const val VAD_ASSET = "ggml-silero-v6.2.0.bin"
    }
}
