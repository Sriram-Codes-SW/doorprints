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

/** The token provider that gives DriveConnectService access to the Drive folder. */
export interface TokenProvider {
  token(): string;
  expiresIn(): number;
}

/**
 * Boundary for Google Drive sign-in. The real implementation adapts GoogleConfig from feat/drive-web-connect;
 * tests use a fake. Injected into DriveConnectService.
 */
export interface DriveSignIn {
  /** True if this browser can reach Google's OAuth and the app has the Google config. */
  available(): boolean;
  /** Opens Google's sign-in, returns a token provider, or throws if cancelled. Never rejects a user's cancellation. */
  connect(): Promise<TokenProvider>;
  /** Forgets the OAuth token and revokes the grant. */
  disconnect(): void;
}
