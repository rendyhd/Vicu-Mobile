package com.rendyhd.vicu.ui.screens.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.rendyhd.vicu.ui.topViewController
import com.rendyhd.vicu.util.IosPlatformFiles
import com.rendyhd.vicu.util.Logger
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImageJPEGRepresentation
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.UniformTypeIdentifiers.UTTypeText
import platform.VisionKit.VNDocumentCameraScan
import platform.VisionKit.VNDocumentCameraViewController
import platform.VisionKit.VNDocumentCameraViewControllerDelegateProtocol
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

// Picked files are copied into the app before the callback (the picker's copies are temporary);
// scanned pages are written as JPEG there. The notes prepare them again (NotesFiles) and the copies
// in Caches are swept with the other staged files.

@Composable
actual fun rememberNotesFilePicker(onPicked: (List<String>) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    val delegate = remember { NotesDocumentDelegate(callback) }
    return {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypePlainText, UTTypeText, UTTypePDF, UTTypeImage), asCopy = true)
        picker.allowsMultipleSelection = true
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** VisionKit's document camera: edge detection, crop, several pages. Null where it is not supported (the simulator). */
@Composable
actual fun rememberNotesScanner(onScanned: (List<String>) -> Unit): (() -> Unit)? {
    val callback = rememberUpdatedState(onScanned)
    val delegate = remember { ScanDelegate(callback) }
    if (!VNDocumentCameraViewController.supported) return null
    return {
        val scanner = VNDocumentCameraViewController()
        scanner.delegate = delegate
        topViewController()?.presentViewController(scanner, animated = true, completion = null)
    }
}

private class NotesDocumentDelegate(
    private val callback: State<(List<String>) -> Unit>,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val paths = didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull { url ->
            IosPlatformFiles.stage(url).also { NSFileManager.defaultManager.removeItemAtURL(url, null) }
        }
        if (paths.isNotEmpty()) callback.value(paths)
    }
}

private class ScanDelegate(
    private val callback: State<(List<String>) -> Unit>,
) : NSObject(), VNDocumentCameraViewControllerDelegateProtocol {

    override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFinishWithScan: VNDocumentCameraScan) {
        controller.dismissViewControllerAnimated(true, completion = null)
        val scan = didFinishWithScan
        // Encoding the pages takes a moment: off the main thread, then back for the callback.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val paths = (0 until scan.pageCount.toInt()).mapNotNull { index ->
                UIImageJPEGRepresentation(scan.imageOfPageAtIndex(index.toULong()), 0.9)?.let { IosPlatformFiles.stage(it, "page-${index + 1}.jpg") }
            }
            dispatch_async(dispatch_get_main_queue()) { if (paths.isNotEmpty()) callback.value(paths) }
        }
    }

    override fun documentCameraViewControllerDidCancel(controller: VNDocumentCameraViewController) {
        controller.dismissViewControllerAnimated(true, completion = null)
    }

    override fun documentCameraViewController(controller: VNDocumentCameraViewController, didFailWithError: NSError) {
        Logger.w("NotesScanner", "The document camera failed: ${didFailWithError.localizedDescription}")
        controller.dismissViewControllerAnimated(true, completion = null)
    }
}
