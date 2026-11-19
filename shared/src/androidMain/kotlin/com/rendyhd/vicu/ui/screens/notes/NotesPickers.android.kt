package com.rendyhd.vicu.ui.screens.notes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ui.rememberCameraCapture
import com.rendyhd.vicu.util.Logger

@Composable
actual fun rememberNotesFilePicker(onPicked: (List<String>) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) latest(uris.map { it.toString() })
    }
    return { launcher.launch(arrayOf("text/*", "application/pdf", "image/*", "application/octet-stream")) }
}

/**
 * Play services' document scanner (edge detection, crop, several pages; no camera permission of
 * Vicu's own). Without Play services, or when the scanner cannot start (it fetches its parts the
 * first time), the system camera takes one page per tap instead.
 */
@Composable
actual fun rememberNotesScanner(onScanned: (List<String>) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onScanned)
    val camera = rememberCameraCapture { uri -> latest(listOf(uri)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty().map { it.imageUri.toString() }
        if (pages.isNotEmpty()) latest(pages)
    }
    val hasPlayServices = remember {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val activity = remember(context) { context.findActivity() }
    if (!hasPlayServices || activity == null) return camera
    return {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(AiContract.limits.notesPagesMax)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e ->
                Logger.w("NotesScanner", "The document scanner could not start: ${e.message}")
                camera?.invoke()
            }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
