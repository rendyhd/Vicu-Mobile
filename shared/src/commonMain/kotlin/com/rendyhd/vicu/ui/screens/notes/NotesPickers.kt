package com.rendyhd.vicu.ui.screens.notes

import androidx.compose.runtime.Composable

/**
 * Picks files of notes (`docs/ai-assistant-v1.md`, section 4.9): text, Markdown, PDFs and pictures,
 * several at once. [onPicked] gets content addresses or paths in the order picked.
 */
@Composable
expect fun rememberNotesFilePicker(onPicked: (List<String>) -> Unit): () -> Unit

/**
 * The camera's document scanner for pages of notes (several pages, cropped); null where the device
 * has no camera. [onScanned] gets the pages' pictures in order.
 */
@Composable
expect fun rememberNotesScanner(onScanned: (List<String>) -> Unit): (() -> Unit)?
