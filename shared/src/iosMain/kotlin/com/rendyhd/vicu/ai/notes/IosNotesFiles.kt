package com.rendyhd.vicu.ai.notes

import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.util.IosPaths
import com.rendyhd.vicu.util.Logger
import com.rendyhd.vicu.util.ioDispatcher
import com.rendyhd.vicu.util.randomUuid
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Instant

/**
 * Notes' files on iOS (`docs/ai-assistant-v1.md`, section 4.9.2): text files read as UTF-8, PDFs
 * through PDFKit (each page's own text, or drawn as a picture when it has too little), pictures
 * drawn again upright at most `notes_image_long_edge` pixels on white and written as JPEG, which
 * drops their metadata. Kept in Application Support (out of backups) while the notes are open.
 */
class IosNotesFiles : NotesFiles {
    private val dir: String get() = IosPaths.excludedSubdirectory(IosPaths.applicationSupport, DIR)

    private val maxBytes: Long get() = AiContract.limits.notesFileMaxMb * 1024L * 1024L

    override suspend fun open(uri: String, maxPages: Int): NotesSource = withContext(ioDispatcher) {
        val path = if (uri.startsWith("file://")) NSURL.URLWithString(uri)?.path ?: uri.removePrefix("file://") else uri
        val name = path.substringAfterLast('/')
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (size > maxBytes) return@withContext NotesSource(name, problem = NotesFileProblem.SIZE)
        val date = (attributes?.get(NSFileModificationDate) as? NSDate)?.let(::localDate)
        val extension = name.substringAfterLast('.', "").lowercase()
        try {
            when (extension) {
                "pdf" -> openPdf(path, name, maxPages, date)
                "txt", "md", "markdown", "text" -> {
                    val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
                        ?: return@withContext NotesSource(name, problem = NotesFileProblem.UNREADABLE)
                    NotesSource(name, listOf(SourcePage(PageKind.TEXT, text.trim())), date)
                }
                "jpg", "jpeg", "png", "heic", "heif", "gif", "tif", "tiff", "webp", "bmp" -> {
                    val image = UIImage.imageWithContentsOfFile(path) ?: return@withContext NotesSource(name, problem = NotesFileProblem.IMAGE)
                    val prepared = prepare(image) ?: return@withContext NotesSource(name, problem = NotesFileProblem.IMAGE)
                    NotesSource(name, listOf(SourcePage(PageKind.IMAGE, imagePath = prepared)), date)
                }
                else -> NotesSource(name, problem = NotesFileProblem.TYPE)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not open a file of notes: ${e.message}")
            NotesSource(name, problem = NotesFileProblem.UNREADABLE)
        }
    }

    private fun openPdf(path: String, name: String, maxPages: Int, date: LocalDate?): NotesSource {
        val copy = "$dir/${randomUuid()}.pdf"
        if (!NSFileManager.defaultManager.copyItemAtPath(path, copy, null)) return NotesSource(name, problem = NotesFileProblem.UNREADABLE)
        val document = PDFDocument(uRL = NSURL.fileURLWithPath(copy))
        if (document.isLocked()) {
            NSFileManager.defaultManager.removeItemAtPath(copy, null)
            return NotesSource(name, problem = NotesFileProblem.PASSWORD)
        }
        val count = document.pageCount.toInt()
        val pages = mutableListOf<SourcePage>()
        for (index in 0 until min(count, maxPages)) {
            val page = document.pageAtIndex(index.toULong()) ?: continue
            val text = page.string
            pages += if (NotesFiles.hasText(text)) {
                SourcePage(PageKind.PDF_TEXT, text = text!!.trim(), pdfPath = copy, pdfIndex = index, textLayer = text.trim())
            } else {
                SourcePage(PageKind.PDF_IMAGE, imagePath = draw(page), pdfPath = copy, pdfIndex = index, textLayer = text?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
        return NotesSource(name, pages, date, morePages = count > maxPages)
    }

    override suspend fun drawPdfPage(pdfPath: String, index: Int): String? = withContext(ioDispatcher) {
        PDFDocument(uRL = NSURL.fileURLWithPath(pdfPath)).pageAtIndex(index.toULong())?.let(::draw)
    }

    /** A PDF page drawn on white at most `notes_image_long_edge` pixels on its long edge. */
    private fun draw(page: PDFPage): String? {
        val bounds: CValue<CGRect> = page.boundsForBox(kPDFDisplayBoxMediaBox)
        val (width, height) = bounds.useContents { size.width to size.height }
        if (width <= 0.0 || height <= 0.0) return null
        val edge = AiContract.limits.notesImageLongEdge.toDouble()
        val scale = min(edge / max(width, height), 4.0)
        val image = page.thumbnailOfSize(CGSizeMake(width * scale, height * scale), forBox = kPDFDisplayBoxMediaBox)
        return writeJpeg(image)
    }

    /** Drawn again upright (UIKit applies the orientation), scaled, on white, at scale 1. */
    private fun prepare(image: UIImage): String? {
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        val edge = AiContract.limits.notesImageLongEdge.toDouble()
        val scale = min(1.0, edge / max(width, height))
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
            setScale(1.0)
            setOpaque(true)
        }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(width * scale, height * scale), format = format)
        val drawn = renderer.imageWithActions { _ ->
            UIColor.whiteColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, width * scale, height * scale))
            image.drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale))
        }
        return writeJpeg(drawn)
    }

    private fun writeJpeg(image: UIImage): String? {
        val data = UIImageJPEGRepresentation(image, JPEG_QUALITY) ?: return null
        val path = "$dir/${randomUuid()}.jpg"
        return if (data.writeToFile(path, atomically = true)) path else null
    }

    override suspend fun readImage(path: String): ByteArray? = withContext(ioDispatcher) {
        NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    override suspend fun clear(keep: Set<String>) {
        withContext(ioDispatcher) {
            val fm = NSFileManager.defaultManager
            fm.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>()?.forEach { entry ->
                val full = "$dir/$entry"
                if (full !in keep) fm.removeItemAtPath(full, null)
            }
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
        return bytes
    }

    private fun localDate(date: NSDate): LocalDate =
        Instant.fromEpochMilliseconds((date.timeIntervalSince1970 * 1000).toLong()).toLocalDateTime(TimeZone.currentSystemDefault()).date

    private companion object {
        const val DIR = "notes"
        const val TAG = "NotesFiles"
        const val JPEG_QUALITY = 0.85
    }
}
