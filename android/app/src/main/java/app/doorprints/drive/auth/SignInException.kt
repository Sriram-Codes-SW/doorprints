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

package app.doorprints.drive.auth

/** Why the Drive token could not be had (web: `SignInError`). Never carries a token; the message is the kind. */
class SignInException(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** Nothing was granted, or the person unticked the Drive permission (granular consent): fail closed. */
        DENIED,

        /** The person closed the prompt without answering: ask again later, do not say "refused". */
        CANCELLED,

        /** Google needs a consent screen and nobody is there to show it (a background run): connect again from the app. */
        CONSENT_REQUIRED,

        OFFLINE,

        /** Google Play services missing, out of date or not answering. */
        UNAVAILABLE,
    }
}
