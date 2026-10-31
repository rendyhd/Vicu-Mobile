package com.rendyhd.vicu.util

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.rendyhd.vicu.data.local.BehaviorPrefsStore

import kotlinx.coroutines.flow.first

/**
 * Plays a short sound when the user completes a task. Default is the system's
 * notification sound; the user can swap in a custom audio file via Settings.
 *
 * - Reads enabled / URI from [BehaviorPrefsStore] on every play() call so changes
 *   in Settings take effect immediately.
 * - Any failure (missing file, codec issue, no notification sound configured)
 *   is logged and swallowed — completion sound is a non-essential affordance.
 *   Settings' Play button uses [preview], which reports it.
 */
class CompletionSoundPlayer(
    private val context: Context,
    private val prefs: BehaviorPrefsStore,
) {
    companion object {
        private const val TAG = "CompletionSoundPlayer"
    }

    suspend fun play() {
        val current = prefs.getPrefs().first()
        if (current.completionSoundEnabled) start(current.completionSoundUri)
    }

    /** Plays the chosen sound even with the switch off; false when it cannot be played. */
    suspend fun preview(): Boolean = start(prefs.getPrefs().first().completionSoundUri)

    private fun start(customUri: String?): Boolean {
        val uri: Uri? = customUri
            ?.takeIf { it.isNotBlank() }
            ?.toUri()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) {
            Log.d(TAG, "No completion sound URI resolved; silent")
            return false
        }

        val mp = MediaPlayer()
        return try {
            mp.apply {
                setDataSource(context, uri)
                setOnErrorListener { player, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    player.release()
                    true
                }
                setOnCompletionListener { it.release() }
                prepare()
            }
            mp.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play completion sound: ${e.message}")
            mp.release()
            false
        }
    }
}
