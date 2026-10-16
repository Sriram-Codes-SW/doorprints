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

import { sha256Hex } from '../../export/sha256';
import { utf8 } from '../../export/zip';
import { houseAiOutputs } from './house-ai-outputs';
import { HOUSE_AI_SHA256 } from './golden/house-ai-pins.golden';

describe('a house through the on-device AI is what main sent (kinds PR 7 safety test, S4b-BL-211)', () => {
  it('every prompt, document line, selection and request body for a house has its pinned SHA-256', () => {
    const got: Record<string, string> = {};
    for (const [name, text] of Object.entries(houseAiOutputs())) got[name] = sha256Hex(utf8(text));
    expect(got).toEqual(HOUSE_AI_SHA256);
  });
});
