package com.rendyhd.vicu.ai.notes

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.randomUuid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * Notes' files on Android (`docs/ai-assistant-v1.md`, section 4.9.2): text files read as UTF-8, PDFs
 * through [PdfRenderer] (the text layer on Android 15 and later, else every page drawn as a
 * picture), pictures decoded, turned upright, scaled and written again as JPEG without metadata.
 * Everything is kept in `no_backup/notes` while the notes are open.
 */
class AndroidNotesFiles(private val context: Context) : NotesFiles {
    private val dir: File get() = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    private val maxBytes: Long get() = AiContract.limits.notesFileMaxMb * 1024L * 1024L

    override suspend fun open(uri: String, maxPages: Int): NotesSource = withContext(Dispatchers.IO) {
        val parsed = if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri)
        val name = displayName(parsed)
        try {
            val size = sizeOf(parsed)
            if (size != null && size > maxBytes) return@withContext NotesSource(name, problem = NotesFileProblem.SIZE)
            val type = typeOf(parsed, name)
            when {
                type == "application/pdf" -> openPdf(parsed, name, maxPages)
                type.startsWith("image/") -> openImage(parsed, name)
                type.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".txt", true) -> {
                    val bytes = read(parsed) ?: return@withContext NotesSource(name, problem = NotesFileProblem.SIZE)
                    NotesSource(name, listOf(SourcePage(PageKind.TEXT, bytes.toString(Charsets.UTF_8).trim())), lastModified(parsed))
                }
                else -> NotesSource(name, problem = NotesFileProblem.TYPE)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not open a file of notes: ${e.message}")
            NotesSource(name, problem = NotesFileProblem.UNREADABLE)
        }
    }

    private fun openImage(uri: Uri, name: String): NotesSource {
        val bytes = read(uri) ?: return NotesSource(name, problem = NotesFileProblem.SIZE)
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
        val path = prepare(bytes, exif) ?: return NotesSource(name, problem = NotesFileProblem.IMAGE)
        val date = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::exifDate) ?: lastModified(uri)
        return NotesSource(name, listOf(SourcePage(PageKind.IMAGE, imagePath = path)), date)
    }

    private fun openPdf(uri: Uri, name: String, maxPages: Int): NotesSource {
        val copy = File(dir, "${randomUuid()}.pdf")
        val ok = open(uri)?.use { input -> copy.outputStream().use { out -> copyAtMost(input, out) } } == true
        if (!ok) {
            copy.delete()
            return NotesSource(name, problem = NotesFileProblem.SIZE)
        }
        val pages = mutableListOf<SourcePage>()
        var more = false
        try {
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    more = renderer.pageCount > maxPages
                    for (index in 0 until min(renderer.pageCount, maxPages)) {
                        val text = renderer.openPage(index).use { page -> textOf(page) }
                        pages += if (NotesFiles.hasText(text)) {
                            SourcePage(PageKind.PDF_TEXT, text = text!!.trim(), pdfPath = copy.path, pdfIndex = index, textLayer = text.trim())
                        } else {
                            val image = renderer.openPage(index).use { page -> draw(page) }
                            SourcePage(PageKind.PDF_IMAGE, imagePath = image, pdfPath = copy.path, pdfIndex = index, textLayer = text?.trim()?.takeIf { it.isNotEmpty() })
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            copy.delete()
            return NotesSource(name, problem = NotesFileProblem.PASSWORD)
        }
        return NotesSource(name, pages, lastModified(uri), morePages = more)
    }

    /** The page's text layer, where Android can read it (API 35). */
    private fun textOf(page: PdfRenderer.Page): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { page.textContents.joinToString("\n") { it.text } }.getOrNull()
        } else {
            null
        }

    override suspend fun drawPdfPage(pdfPath: String, index: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer -> renderer.openPage(index).use { draw(it) } }
            }
        }.getOrNull()
    }

    /** A PDF page drawn on white at most `notes_image_long_edge` pixels on its long edge. */
    private fun draw(page: PdfRenderer.Page): String? {
        val edge = AiContract.limits.notesImageLongEdge
        val scale = min(edge.toFloat() / max(page.width, page.height), MAX_PDF_SCALE)
        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return writeJpeg(bitmap)
    }

    /** Upright, scaled, on white (a transparent PNG), as JPEG: the metadata is not written again. */
    private fun prepare(bytes: ByteArray, exif: ExifInterface?): String? {
        val edge = AiContract.limits.notesImageLongEdge
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > edge) edge.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                else -> Unit
            }
        }
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val white = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
        Canvas(white).apply {
            drawColor(Color.WHITE)
            drawBitmap(turned, 0f, 0f, null)
        }
        return writeJpeg(white)
    }

    private fun writeJpeg(bitmap: Bitmap): String? {
        val file = File(dir, "${randomUuid()}.jpg")
        val ok = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return if (ok) file.path else null.also { file.delete() }
    }

    override suspend fun readImage(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.readBytes()
    }

    override suspend fun clear(keep: Set<String>) {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.path !in keep }?.forEach { it.delete() }
        }
    }

    private fun open(uri: Uri): InputStream? =
        if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)

    /** The whole file, or null when it is over the limit. */
    private fun read(uri: Uri): ByteArray? = open(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        if (copyAtMost(input, out)) out.toByteArray() else null
    }

    private fun copyAtMost(input: InputStream, out: java.io.OutputStream): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return true
            total += n
            if (total > maxBytes) return false
            out.write(buffer, 0, n)
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun sizeOf(uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path!!).length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun typeOf(uri: Uri, name: String): String {
        val given = if (uri.scheme == ContentResolver.SCHEME_CONTENT) context.contentResolver.getType(uri) else null
        if (given != null && given != "application/octet-stream") return given
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension == "md" || extension == "markdown") return "text/markdown"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: given.orEmpty()
    }

    /** The file's own date, where the provider gives one. */
    private fun lastModified(uri: Uri): LocalDate? {
        val millis = if (uri.scheme == ContentResolver.SCHEME_FILE) {
            File(uri.path!!).lastModified().takeIf { it > 0 }
        } else {
            runCatching {
                context.contentResolver.query(uri, arrayOf(LAST_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }.getOrNull()
        } ?: return null
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }
    }

    private fun exifDate(text: String): LocalDate? = runCatching {
        LocalDate(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt())
    }.getOrNull()

    private companion object {
        const val DIR = "notes"
        const val TAG = "NotesFiles"
        const val JPEG_QUALITY = 85
        const val MAX_PDF_SCALE = 4f
        const val LAST_MODIFIED = "last_modified"
    }
}
