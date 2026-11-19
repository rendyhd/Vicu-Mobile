package com.rendyhd.vicu.ai.notes

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** What a page of notes is, for its label and what Read does with it (section 4.9.2). */
@Serializable
enum class PageKind {
    /** Typed, pasted or shared text, or a text file. */
    TEXT,

    /** A PDF page read from its text layer. */
    PDF_TEXT,

    /** A PDF page drawn as a picture (too little text, or "Read as image"). */
    PDF_IMAGE,

    /** A photo, a scan or a picture file. */
    IMAGE,
    ;

    val isImage: Boolean get() = this == PDF_IMAGE || this == IMAGE
}

/** One page a source gave: its text, or the picture to read (a prepared JPEG kept on the device). */
data class SourcePage(
    val kind: PageKind,
    val text: String = "",
    val imagePath: String? = null,
    /** A PDF page: the copy of the PDF kept while the notes are open, and the page's index in it. */
    val pdfPath: String? = null,
    val pdfIndex: Int? = null,
    /** A PDF page's own text, to go back to after "Read as image". */
    val textLayer: String? = null,
)

/** Why a file gave no pages. */
enum class NotesFileProblem { TYPE, SIZE, UNREADABLE, PASSWORD, IMAGE }

/** A file opened as pages: its name, its pages in order, its date when it has one. */
data class NotesSource(
    val name: String,
    val pages: List<SourcePage> = emptyList(),
    val date: LocalDate? = null,
    val problem: NotesFileProblem? = null,
    /** A PDF with more pages than fit: only the first ones were taken. */
    val morePages: Boolean = false,
)

/**
 * The device side of notes (`docs/ai-assistant-v1.md`, section 4.9.2), per platform: files picked,
 * shared or scanned become pages, pictures are prepared (upright, the long edge at most
 * `notes_image_long_edge` pixels, JPEG, no metadata) and kept in the app's own folder only while
 * the notes are open; they are never uploaded to the Vikunja server.
 */
interface NotesFiles {
    /** Reads a picked, shared or scanned file ([uri] is a content address or a path) into at most [maxPages] pages. */
    suspend fun open(uri: String, maxPages: Int): NotesSource

    /** Draws page [index] of the kept PDF at [pdfPath] as a prepared picture; its path, or null. */
    suspend fun drawPdfPage(pdfPath: String, index: Int): String?

    /** The bytes of a kept picture (JPEG), or null when it is gone. */
    suspend fun readImage(path: String): ByteArray?

    /** Removes every kept file of the notes except [keep]. */
    suspend fun clear(keep: Set<String> = emptySet())

    companion object {
        /** A PDF page with fewer letters or digits than this in its text layer is read as a picture (4.9.2). */
        const val MIN_TEXT_CHARS = 20

        /** Whether a PDF page's text layer holds enough to read it as text. */
        fun hasText(text: String?): Boolean = text != null && text.count { it.isLetterOrDigit() } >= MIN_TEXT_CHARS
    }
}
