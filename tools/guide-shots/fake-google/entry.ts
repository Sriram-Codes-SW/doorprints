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

// Bundled by tools/guide-shots/shots.cjs (esbuild) and injected into the page BEFORE the app starts, for the guide's
// pictures of a CONNECTED Google Drive card. It puts the repository's own in-memory fake Drive behind `window.fetch`
// for www.googleapis.com and stands in for Google's sign-in script, so no account, token or network is involved.
import { FakeDriveHttp } from '../../../web/src/app/data/drive/fake-drive-http';
import { FakeDriveServer } from '../../../web/src/app/data/drive/in-memory-fake-drive';

const server = new FakeDriveServer();
const http = new FakeDriveHttp(server);
const real = window.fetch.bind(window);
window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  return url.startsWith('https://www.googleapis.com/') ? http.fetch(input, init) : real(input, init);
}) as typeof fetch;

// What Google's script would define: a token client whose popup answers at once with a made-up token.
(window as unknown as { google: unknown }).google = {
  accounts: {
    oauth2: {
      initTokenClient: (config: { callback: (r: Record<string, unknown>) => void }) => ({
        requestAccessToken: () =>
          config.callback({ access_token: 'screenshot-only-token', expires_in: 3600, scope: 'https://www.googleapis.com/auth/drive.file' }),
      }),
      // The page asks Google whether the Drive permission was ticked on its consent page; here it always was.
      hasGrantedAllScopes: () => true,
      revoke: (_token: string, done: () => void) => done(),
    },
  },
};
