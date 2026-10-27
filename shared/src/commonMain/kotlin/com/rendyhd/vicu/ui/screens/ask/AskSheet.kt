package com.rendyhd.vicu.ui.screens.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rendyhd.vicu.ai.provider.ChatMessage
import com.rendyhd.vicu.ui.theme.VicuMotion
import com.rendyhd.vicu.ui.theme.rememberReduceMotion

/**
 * Ask over the list it was opened from (`docs/ai-assistant-v1.md`, sections 5 and 6): small at the
 * bottom while nothing is asked yet, so the list stays in sight, and grown to most of the screen once
 * the first question goes out. Back, the close button or a tap above it closes it (and turns the
 * microphone off).
 */
@Composable
fun AskSheet(
    open: Boolean,
    onClose: () -> Unit,
    onTaskClick: (Long) -> Unit,
    viewModel: AskViewModel,
    onStartGuided: (String) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val close = {
        viewModel.micOff()
        onClose()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.micOff() }
    AnimatedVisibility(
        visible = open,
        enter = if (reduceMotion) fadeIn() else fadeIn() + slideInVertically(VicuMotion.defaultSpatialSpec()) { it / 4 },
        exit = if (reduceMotion) fadeOut() else fadeOut() + slideOutVertically(VicuMotion.fastEffectsSpec()) { it / 4 },
    ) {
        NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = open) { close() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val guide by viewModel.guide.collectAsStateWithLifecycle()
        val session = state.session
        // Grown once something was asked: the answers need the room, the list above stays in sight.
        val grown = session != null && (
            session.conversation.messages.any { it is ChatMessage.User } || session.busy || session.error != null
            )
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (grown) 0.32f else 0.12f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            )
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .then(if (grown) Modifier.fillMaxHeight(GROWN_HEIGHT) else Modifier)
                    .then(if (reduceMotion) Modifier else Modifier.animateContentSize(VicuMotion.defaultSpatialSpec()))
                    .semantics { paneTitle = "Ask" },
            ) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Header(state, viewModel, onClose = close)
                    if (grown) {
                        Conversation(state, viewModel, onTaskClick, Modifier.weight(1f))
                    } else if (state.settings.askReady) {
                        // Nothing asked yet: what needs a decision, and questions to start with (section 5.7).
                        AskAgenda(
                            items = guide.agenda,
                            starters = viewModel.starters(guide.inboxId),
                            enabled = session?.busy != true,
                            onItem = { id ->
                                close()
                                onStartGuided(id)
                            },
                            onStarter = { text -> viewModel.send(text) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (state.settings.askReady) {
                        InputBar(state, viewModel)
                    } else {
                        Text(
                            "Ask is off. Turn it on in Settings, Assistant.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: AskUiState, viewModel: AskViewModel, onClose: () -> Unit) {
    var historyOpen by remember { mutableStateOf(false) }
    val about = viewModel.listTitle()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (state.talkMode) "Talk" else "Ask", style = MaterialTheme.typography.titleMedium)
            Text(
                if (about.isNotBlank()) "About $about, or anything else" else "Ask about your tasks, or tap the microphone and talk",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.history.isNotEmpty()) {
            Box {
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Outlined.History, contentDescription = "Earlier conversations")
                }
                DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
                    for (c in state.history) {
                        DropdownMenuItem(
                            text = { Text(c.title.ifBlank { "Conversation" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                historyOpen = false
                                viewModel.open(c)
                            },
                        )
                    }
                }
            }
        }
        IconButton(onClick = viewModel::newChat) { Icon(Icons.Outlined.AddComment, contentDescription = "New chat") }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close Ask") }
    }
}

/** How much of the screen Ask takes once something was asked. */
private const val GROWN_HEIGHT = 0.8f
