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

import type { Lang } from '../i18n/languages';

/**
 * The user guide (an MkDocs site, `guide/`, published by `.github/workflows/pages.yml`), which the **Help** link opens
 * in a new tab (S4b-BL-60): in the header on wider screens, inside *Your data* on phones. English at the root; the
 * Hindi, Tamil and Telugu guides (under review) are built into `hi/`, `ta/` and `te/` beside it (`guide/hooks/i18n.py`),
 * and `guideUrl` picks the one in the app's language. Android has the same link in Settings under *About*.
 */
export const GUIDE_URL = 'https://sriram-codes-sw.github.io/doorprints/';

/** The guide in `lang`: the English root, or the `hi/`, `ta/`, `te/` translation. */
export function guideUrl(lang: Lang): string {
  return lang === 'en' ? GUIDE_URL : `${GUIDE_URL}${lang}/`;
}

/** The guide's section *Is AI worth it for me?* in `lang` (S4b-BL-215), linked from the AI features card. */
export function aiWorthItUrl(lang: Lang): string {
  return `${guideUrl(lang)}settings-and-privacy.html#is-ai-worth-it`;
}
