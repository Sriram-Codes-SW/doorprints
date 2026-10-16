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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/viewing-payload-vectors.json';
import { viewingFromPayload, viewingToPayload } from './viewing';

interface ReadCase {
  name: string;
  id: string;
  payload: Record<string, unknown>;
  candidateId: string | null;
  written?: string;
}

const vectors = vectorsJson as unknown as { format: string; reads: ReadCase[] };

/**
 * The shared viewing payload vectors (`docs/schemas/viewing-payload-vectors.json`, S4b-BL-247): a payload names its
 * candidate as `candidateId`, one stored before as `houseId`, both keys a bad row; what the website writes back says
 * `candidateId`, byte for byte as the phone and the server write it. Twins: Kotlin `ViewingPayloadVectorsTest`, Java
 * `ViewingPayloadVectorsTest`. docs/06 TC-U-208.
 */
describe('viewing payload vectors', () => {
  it('is the vectors file this reader knows', () => {
    expect(vectors.format).toBe('doorprints-viewing-payload-vectors/1');
    expect(vectors.reads.filter((c) => c.candidateId !== null && 'houseId' in c.payload).length).toBeGreaterThanOrEqual(2);
    expect(vectors.reads.filter((c) => c.candidateId !== null && 'candidateId' in c.payload).length).toBeGreaterThanOrEqual(2);
  });

  for (const c of vectors.reads) {
    it(c.name, () => {
      const viewing = viewingFromPayload(c.id, c.payload);
      if (c.candidateId === null) {
        expect(viewing).toBeNull();
        return;
      }
      expect(viewing?.houseId).toBe(c.candidateId);
      const written = JSON.stringify(viewingToPayload(viewing!));
      expect(written).toBe(c.written);
      expect(written).not.toContain('"houseId"');
    });
  }
});
