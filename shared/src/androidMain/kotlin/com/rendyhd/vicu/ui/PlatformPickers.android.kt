package com.rendyhd.vicu.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.rendyhd.vicu.util.AppMessages
import com.rendyhd.vicu.util.CameraPhotos
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.TimeSource
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
actual fun rememberImagePicker(onImagePicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onImagePicked(it.toString()) }
    }
    return {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
actual fun rememberFilePicker(onFilePicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onFilePicked(it.toString()) }
    }
    return {
        launcher.launch("*/*")
    }
}

// The camera app writes into a file of ours (cache/camera, served by the file provider), so the photo
// never reaches the Gallery through Vicu. The app asks for no CAMERA permission: with it declared,
// the system would refuse the camera app until it was granted. The file being written is kept in
// the saved state, because the system may end Vicu while the camera is open.
@Composable
actual fun rememberCameraCapture(onCaptured: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val timeSource = koinInject<TimeSource>()
    val messages = koinInject<AppMessages>()
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onCaptured)
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { taken ->
        val uri = target ?: return@rememberLauncherForActivityResult
        target = null
        scope.launch {
            val kept = withContext(Dispatchers.IO) { CameraFiles.finish(context, uri, taken) }
            if (kept) latest(uri)
        }
    }
    val available = remember { CameraFiles.canCapture(context) }
    if (!available) return null
    return {
        val uri = CameraFiles.newPhoto(context, CameraPhotos.fileName(timeSource.now(), timeSource.zone()))
        target = uri.toString()
        try {
            launcher.launch(uri)
        } catch (_: ActivityNotFoundException) {
            target = null
            messages.post("No camera app found")
        }
    }
}

private object CameraFiles {
    private const val DIR = "camera"
    private const val KEEP_MILLIS = 3L * 24 * 60 * 60 * 1000

    fun canCapture(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(pm) != null
    }

    /**
     * A new, empty file for the camera to write. Photos from earlier days are swept here: by then
     * they were uploaded, or copied into the offline upload store when they had to wait.
     */
    fun newPhoto(context: Context, name: String): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MILLIS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        var file = File(dir, name)
        var n = 2
        while (file.exists()) file = File(dir, name.replace(".jpg", "-${n++}.jpg"))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Keeps the photo when the camera took one (removing its location), else deletes the file.
     * Some camera apps answer "cancelled" after writing the photo, so a written file counts.
     */
    fun finish(context: Context, uriString: String, taken: Boolean): Boolean {
        val name = Uri.parse(uriString).lastPathSegment ?: return false
        val file = File(File(context.cacheDir, DIR), name)
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return false
        }
        if (!taken) Logger.i(TAG, "The camera answered cancelled after writing a photo; keeping it")
        removeLocation(file)
        return true
    }

    // The camera app may have written where the photo was taken; people the task is shared with
    // would see it. Picked photos already arrive without it (the photo picker removes it).
    private fun removeLocation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            if (GPS_TAGS.none { exif.getAttribute(it) != null }) return
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
        } catch (e: Exception) {
            Logger.w(TAG, "Could not remove the location from a photo: ${e.message}")
        }
    }

    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
    )

    private const val TAG = "CameraCapture"
}

// OpenDocument gives a URI that survives reboots: the picker grants persistent read permission,
// which is claimed again here before the URI is stored.
@Composable
actual fun rememberCompletionSoundPicker(onSoundPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some pickers don't grant persistable permission. The URI may still
                // work for the current process; if not, the player will log and skip.
            }
            onSoundPicked(uri.toString())
        }
    }
    return { launcher.launch(arrayOf("audio/*")) }
}

// The system's document picker: calendar files first, and the types file managers give .ics files
// they do not know. Read once, on IO; no permission is kept.
@Composable
actual fun rememberCalendarFilePicker(onPicked: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_CALENDAR_FILE_BYTES) return@use null
                            out.write(buffer, 0, read)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                }.getOrNull()
            }
            latest(text)
        }
    }
    return {
        launcher.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain"))
    }
}
