package com.rendyhd.vicu.voice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.util.DateDisplayFormat
import com.rendyhd.vicu.util.TextTaskCreator
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Adds a task by voice without opening the app (the Quick add widget's mic, the task list widget's
 * mic, a pinned copy of the former "Add by voice" launcher shortcut): the system speech screen
 * hears the task, which is read like quick add ("Call mom tomorrow 3pm #Family") and stored on the
 * device; a toast says what was added. No microphone permission: the speech screen is the system's.
 * Without a speech service it opens quick add instead, where the keyboard's mic works.
 */
class VoiceAddActivity : ComponentActivity() {
    private val creator: TextTaskCreator by inject()

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode != RESULT_OK || text.isNullOrBlank()) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val format = DateDisplayFormat.system(DateFormat.is24HourFormat(this@VoiceAddActivity))
            val message = when (val added = creator.create(text, format)) {
                is TextTaskCreator.Result.Created -> added.confirmation
                is TextTaskCreator.Result.Failed -> added.message
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation) is already waiting for the speech screen's answer.
        if (savedInstanceState != null) return
        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What's the task?")
        try {
            speech.launch(listen)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOW_TASK_ENTRY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }
}
