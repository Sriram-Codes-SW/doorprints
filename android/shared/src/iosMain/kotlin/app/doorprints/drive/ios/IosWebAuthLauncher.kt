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

package app.doorprints.drive.ios

import app.doorprints.drive.auth.browser.BrowserLauncher
import app.doorprints.drive.auth.browser.BrowserRedirect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/**
 * Google's sign-in page in the system's own web authentication sheet (docs/15 §5.5): [BrowserLauncher] over
 * `ASWebAuthenticationSession`, no Google SDK and no web view of ours (the page cannot be read or scripted by the app, and
 * a person already signed in to Google in Safari meets a consent screen only). The session waits for [callbackScheme]; its
 * completion hands the callback URL to [BrowserRedirect.deliver], which checks the address and the `state` and completes
 * the one pending request, or ends that request as cancelled when the person closes the sheet (or the callback is refused:
 * the session is over either way, so waiting would only run out the clock). The authorisation code is a secret: it is
 * handed on, never logged and never in an error.
 *
 * Starts on the main thread (the framework needs it) and keeps the session and the presentation anchor provider alive
 * until it completes, because the system holds both weakly. A second launch while one is up replaces it (the redirect
 * already ended the first request). Compiled here, not run: Google's page needs a device and the owner's client (MT-86).
 */
@OptIn(ExperimentalForeignApi::class)
class IosWebAuthLauncher(
    private val callbackScheme: String,
    private val redirect: BrowserRedirect,
) : BrowserLauncher {
    private var session: ASWebAuthenticationSession? = null
    private var anchors: AnchorProvider? = null

    override fun launch(url: String): Boolean {
        val target = NSURL.URLWithString(url) ?: return false
        var started = false
        onMain { started = start(target) }
        return started
    }

    private fun start(target: NSURL): Boolean {
        val old = session
        var created: ASWebAuthenticationSession? = null
        created = ASWebAuthenticationSession(target, callbackScheme) { callback: NSURL?, error: NSError? ->
            // A session this launcher replaced ends as cancelled by the system; that is not an answer to the new request.
            if (session === created) {
                session = null
                anchors = null
                when (val ended = resultOf(callback?.absoluteString, error?.code)) {
                    is WebAuthEnd.Callback -> if (!redirect.deliver(ended.url)) redirect.cancelPending()
                    WebAuthEnd.Cancelled -> redirect.cancelPending()
                }
            }
        }
        val provider = AnchorProvider()
        created.presentationContextProvider = provider
        session = created
        anchors = provider
        old?.cancel()
        val ok = created.start()
        if (!ok) {
            session = null
            anchors = null
        }
        return ok
    }

    /** The sign-in sheet is on screen no longer in use (the person pressed *Cancel* in Doorprints while it was up). */
    fun cancel() {
        onMain { session?.cancel() }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_sync(dispatch_get_main_queue(), block)
    }

    /** The window the sheet slides over: the key window of the foreground scene. */
    private class AnchorProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .firstNotNullOfOrNull { it.keyWindow }
                ?: UIWindow()
    }

    /** What the session's completion came to. */
    internal sealed interface WebAuthEnd {
        class Callback(val url: String) : WebAuthEnd {
            override fun toString() = "Callback"
        }

        data object Cancelled : WebAuthEnd
    }

    internal companion object {
        /** A callback URL is handed to the redirect; a closed sheet, an error or no URL ends the request. */
        fun resultOf(callbackUrl: String?, errorCode: Long?): WebAuthEnd = when {
            errorCode != null && errorCode == ASWebAuthenticationSessionErrorCodeCanceledLogin -> WebAuthEnd.Cancelled
            callbackUrl != null && errorCode == null -> WebAuthEnd.Callback(callbackUrl)
            else -> WebAuthEnd.Cancelled
        }
    }
}
