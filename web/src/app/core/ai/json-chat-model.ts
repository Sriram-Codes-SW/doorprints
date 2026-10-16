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

/**
 * What the on-device AI needs from a provider (docs/03 §13.2, ADR-35): one JSON answer for a prompt and a schema, and a
 * test call. The three prompts, their safety code and their checks (`OnDeviceAiService`, ai-core.ts) depend on this and
 * on nothing provider-specific; each kind (`gemini`, `openai-compatible`) is one implementation.
 */
export interface JsonChatModel {
  /**
   * The model's JSON answer as text (the caller parses it). `schema` is in the Gemini dialect (the `responseSchema`
   * records of `on-device-ai.service.ts`); an adapter turns it into what its provider wants. Rejects with an
   * `OnDeviceAiError`, or with the network error when the device is offline (Gemini).
   */
  generateJson(system: string, user: string, schema: object, temperature: number): Promise<string>;

  /** One small real call that proves the URL, the key and the model work. Rejects as {@link generateJson} does. */
  ping(): Promise<void>;
}
