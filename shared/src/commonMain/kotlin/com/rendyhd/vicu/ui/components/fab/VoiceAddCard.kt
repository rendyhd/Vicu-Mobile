package com.rendyhd.vicu.ui.components.fab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rendyhd.vicu.ui.theme.rememberReduceMotion
import com.rendyhd.vicu.voice.VoiceAddState
import com.rendyhd.vicu.voice.VoicePhase

/**
 * The card a widget's mic shows over the home screen while local speech hears a task
 * ([com.rendyhd.vicu.voice.VoiceAddSession]): the words as they are transcribed, Cancel and Done.
 * A tap beside the card cancels.
 */
@Composable
fun VoiceAddCard(state: VoiceAddState, onDone: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val listening = state.phase == VoicePhase.LISTENING
    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                // Taps on the card stay on it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = "Say a task" },
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    MicPulse(level = state.level, listening = listening, reduceMotion = rememberReduceMotion())
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = state.text.ifBlank { if (listening) "Listening…" else "Turning your words into text…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.text.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onDone, enabled = listening) { Text("Done") }
                }
            }
        }
    }
}
