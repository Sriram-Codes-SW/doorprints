/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * The system sheets of the iPhone's copies and imports (S4b-BL-81) and of a viewing's calendar file (S4b-BL-92a): the
 * share sheet (`UIActivityViewController`), the Files app's *Save to Files* and *Open* pickers
 * (`UIDocumentPickerViewController`) and the preview a saved copy opens in (`UIDocumentInteractionController`), each
 * presented over whatever is on top. UIKit keeps delegates weakly, so the one in use is held here. Main thread.
 */
internal object IosSheets {
    private var pickerDelegate: PickerDelegate? = null
    private var preview: UIDocumentInteractionController? = null
    private var previewDelegate: PreviewDelegate? = null

    /** The view controller on top: the key window's root and whatever it presents; null before there is a window. */
    private fun top(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstNotNullOfOrNull { it.keyWindow }
        var controller = window?.rootViewController
        while (controller?.presentedViewController != null) controller = controller.presentedViewController
        return controller
    }

    /** The share sheet for the file at [url]; false when there is nothing to show it over. */
    fun share(url: NSURL): Boolean {
        val top = top() ?: return false
        val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        // An iPad shows it as a popover, which needs an anchor.
        sheet.popoverPresentationController?.sourceView = top.view
        top.presentViewController(sheet, animated = true, completion = null)
        return true
    }

    /** Opens the file at [url] in the system's preview (Quick Look); false when it cannot show that kind of file. */
    fun open(url: NSURL): Boolean {
        val top = top() ?: return false
        val controller = UIDocumentInteractionController.interactionControllerWithURL(url)
        val delegate = PreviewDelegate(top)
        controller.delegate = delegate
        preview = controller
        previewDelegate = delegate
        return controller.presentPreviewAnimated(true)
    }

    /**
     * *Save to Files* for the finished copy at [file]: suspends until the person picks a folder (the destination's
     * `file://` URL) or backs out (null). A cancelled caller takes the picker down. Throws when nothing can show it.
     */
    suspend fun saveToFiles(file: NSURL): String? = suspendCancellableCoroutine { continuation ->
        val top = top() ?: throw IllegalStateException("no window to show the picker over")
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(file), asCopy = true)
        val delegate = PickerDelegate { urls -> if (continuation.isActive) continuation.resume(urls?.firstOrNull()?.absoluteString) }
        picker.delegate = delegate
        pickerDelegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
        continuation.invokeOnCancellation {
            dispatch_async(dispatch_get_main_queue()) { picker.dismissViewControllerAnimated(true, completion = null) }
        }
    }

    /**
     * The Files app's picker for a backup (a ZIP, or the bare `data.json` of a server's export); [onPicked] gets the
     * file, still security-scoped, or null. False when there is nothing to show it over.
     */
    fun pickBackup(onPicked: (NSURL?) -> Unit): Boolean {
        val top = top() ?: return false
        // Not a copy: the file is read once, under its security scope, into the app's own staging folder.
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeZIP, UTTypeJSON), asCopy = false)
        picker.allowsMultipleSelection = false
        val delegate = PickerDelegate { urls -> onPicked(urls?.firstOrNull()) }
        picker.delegate = delegate
        pickerDelegate = delegate
        top.presentViewController(picker, animated = true, completion = null)
        return true
    }

    /** A picker's answer: the picked files, or null when the person backed out. */
    private class PickerDelegate(private val done: (List<NSURL>?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            done(null)
        }
    }

    private class PreviewDelegate(private val over: UIViewController) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
        override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController =
            over
    }
}
