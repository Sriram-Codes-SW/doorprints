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
import type { HttpClient } from '@angular/common/http';
import { NEVER, of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { REQUEST_TIMEOUT_MS } from './ai-request';
import { ANSWER_SCHEMA, GeminiChatModel, OnDeviceAiError } from './on-device-ai.service';

/** An HttpClient whose post answers with the given observable. */
const http = (post: () => unknown) => ({ post } as unknown as HttpClient);

describe('the Gemini path has the same request limit as the other kinds (S4b-BL-238)', () => {
  it('gives up on a call that never answers and reports it as unavailable, the words the other kinds use', async () => {
    const model = new GeminiChatModel(http(() => NEVER), 'AIzaTestKey1234', 'quality', 20);
    await expect(model.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1)).rejects.toMatchObject({ kind: 'unavailable' });
    await expect(model.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1)).rejects.toBeInstanceOf(OnDeviceAiError);
  });

  it('passes an answer that arrives in time through unchanged', async () => {
    const model = new GeminiChatModel(http(() => of({ candidates: [{ content: { parts: [{ text: '{"answer":"Yes"}' }] } }] })), 'k', 'quality', 20);
    await expect(model.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1)).resolves.toBe('{"answer":"Yes"}');
  });

  it('defaults to the 60 s limit of postAiJson, so the app never has to pass one', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(60_000);
    const model = new GeminiChatModel(http(() => NEVER), 'k') as unknown as { timeoutMs: number };
    expect(model.timeoutMs).toBe(REQUEST_TIMEOUT_MS);
  });
});
