package com.rendyhd.vicu.ui

import androidx.compose.runtime.Composable

@Composable
expect fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit

@Composable
expect fun rememberFilePicker(onFilePicked: (String) -> Unit): () -> Unit

/**
 * Opens the phone's own camera for one photo; the callback gets the photo the way
 * [rememberImagePicker] hands a picked one over (a content URI on Android, a staged path on iOS).
 * The photo is kept only inside the app, never in the photo library, and without its location.
 * Null on a device without a camera, where callers offer the photo picker alone.
 */
@Composable
expect fun rememberCameraCapture(onCaptured: (String) -> Unit): (() -> Unit)?

/**
 * Picks a sound file for the completion sound and keeps access to it across restarts. The result
 * is a string the platform's sound player understands (a content URI on Android).
 */
@Composable
expect fun rememberCompletionSoundPicker(onSoundPicked: (String) -> Unit): () -> Unit

/** The largest calendar file read for an import (contract 20.10); a bigger one is refused. */
const val MAX_CALENDAR_FILE_BYTES: Long = 5L * 1024 * 1024

/**
 * Picks a calendar file (`.ics`) and reads it: the callback gets its text, or null when it could
 * not be read or is larger than [MAX_CALENDAR_FILE_BYTES]. Nothing is kept: the import is once.
 */
@Composable
expect fun rememberCalendarFilePicker(onPicked: (String?) -> Unit): () -> Unit
