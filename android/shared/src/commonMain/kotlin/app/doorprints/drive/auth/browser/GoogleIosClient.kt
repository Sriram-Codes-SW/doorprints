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

package app.doorprints.drive.auth.browser

/**
 * The iPhone's Google OAuth client (docs/15 §2.4, §5.5), from the `GoogleIOSClientId` key of Info.plist, which the build
 * fills from the `GOOGLE_IOS_CLIENT_ID` build setting (`ios/Config/Drive.xcconfig`, the owner's local overrides). **No
 * client id is in the repository**: with none, Drive says "not available" exactly as Android does without a client.
 *
 * Google's iOS clients redirect to the **reversed client id** as a custom URL scheme: client id
 * `123-abc.apps.googleusercontent.com` redirects to `com.googleusercontent.apps.123-abc:/oauth2redirect`
 * ([urlScheme], [redirectUri]). A value that is not shaped like a client id is no client: an unresolved `$(...)` of an
 * unset build setting, a half-typed id or anything with characters a URL scheme cannot hold.
 */
class GoogleIosClient private constructor(val clientId: String, private val prefix: String) {
    /** `com.googleusercontent.apps.<prefix>`, the scheme `ASWebAuthenticationSession` waits for. */
    val urlScheme: String get() = REVERSED_PREFIX + prefix

    val redirectUri: String get() = "$urlScheme:$REDIRECT_PATH"

    override fun toString() = "GoogleIosClient"

    companion object {
        const val SUFFIX = ".apps.googleusercontent.com"
        private const val REVERSED_PREFIX = "com.googleusercontent.apps."
        const val REDIRECT_PATH = "/oauth2redirect"
        private val PREFIX = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        /** The client of [raw], or null when [raw] is empty, unresolved or not a Google client id. */
        fun of(raw: String?): GoogleIosClient? {
            val id = raw?.trim().orEmpty()
            if (!id.endsWith(SUFFIX)) return null
            val prefix = id.removeSuffix(SUFFIX)
            return if (PREFIX.matches(prefix)) GoogleIosClient(id, prefix) else null
        }
    }
}
