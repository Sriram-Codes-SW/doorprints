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

import { AI_QUALITY_KEY } from '../storage-keys';

/**
 * The *AI speed and cost* setting (S4b-BL-198 step 2, docs/ai/ai-design.md 13.2): how long the model may think before it
 * answers. Thinking is about three quarters of the output cost, so less of it is cheaper and faster, and in the golden-set
 * runs the answers were as good. Kept on this device (localStorage, a preference, not a secret); only the `gemini` kind
 * reads it, so for every other service it is kept but ignored.
 */

/** Quality is the model's own default (nothing is sent); Balanced and Economy ask for less thinking. */
export type AiQuality = 'quality' | 'balanced' | 'economy';

/** The choices, in the order the screen lists them. */
export const AI_QUALITIES: readonly AiQuality[] = ['quality', 'balanced', 'economy'];

/** What nothing saved, or anything unknown, reads as: today's behaviour. */
export const DEFAULT_AI_QUALITY: AiQuality = 'quality';

/** The `thinkingLevel` values Gemini 3.x accepts that this setting uses (`MINIMAL` and `HIGH` exist too, but are not offered). */
export type GeminiThinkingLevel = 'MEDIUM' | 'LOW';

/**
 * Gemini's `generationConfig.thinkingConfig.thinkingLevel` for a choice, or null for Quality, which sends no
 * `thinkingConfig` at all (the model decides, as before this setting existed).
 */
export function geminiThinkingLevel(quality: AiQuality): GeminiThinkingLevel | null {
  if (quality === 'balanced') return 'MEDIUM';
  if (quality === 'economy') return 'LOW';
  return null;
}

/** A stored text as a choice: exactly one of the three names, else Quality. */
export function parseAiQuality(raw: string | null | undefined): AiQuality {
  return AI_QUALITIES.find((q) => q === raw) ?? DEFAULT_AI_QUALITY;
}

/** The saved choice; Quality when none is saved, the value is unknown, or storage is blocked. */
export function readAiQuality(): AiQuality {
  try {
    return parseAiQuality(localStorage.getItem(AI_QUALITY_KEY));
  } catch {
    return DEFAULT_AI_QUALITY;
  }
}

/** Saves a choice (an unknown one is saved as Quality) and returns what was saved. */
export function saveAiQuality(quality: AiQuality): AiQuality {
  const saved = parseAiQuality(quality);
  try {
    localStorage.setItem(AI_QUALITY_KEY, saved);
  } catch {
    // Storage unavailable: the choice holds for this page only.
  }
  return saved;
}

/** Removes the saved choice (*Remove key*). */
export function clearAiQuality(): void {
  try {
    localStorage.removeItem(AI_QUALITY_KEY);
  } catch {
    // ignore
  }
}
