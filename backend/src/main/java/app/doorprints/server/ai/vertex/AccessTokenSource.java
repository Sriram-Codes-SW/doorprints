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

package app.doorprints.server.ai.vertex;

/**
 * Supplies the OAuth 2.0 access token sent as {@code Authorization: Bearer ...} to Vertex AI. Production uses
 * {@link GoogleAccessTokenSource} (Application Default Credentials); tests pass a fixed token.
 */
@FunctionalInterface
public interface AccessTokenSource {

    /** A currently valid access token (refreshed when needed). Never logged, never put in exception messages. */
    String accessToken();

    /** Project billed for quota ({@code x-goog-user-project}), or {@code null} to bill the resource's project. */
    default String quotaProjectId() {
        return null;
    }
}
