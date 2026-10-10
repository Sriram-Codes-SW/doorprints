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

import { beforeEach, describe, expect, it } from 'vitest';
import { AI_QUALITIES, DEFAULT_AI_QUALITY, clearAiQuality, geminiThinkingLevel, parseAiQuality, readAiQuality, saveAiQuality } from './ai-quality';
import { AI_QUALITY_KEY, STORAGE_PREFIX } from '../storage-keys';

/**
 * TC-U-190 (docs/06): the *AI speed and cost* setting (S4b-BL-198 step 2, docs/ai/ai-design.md 13.2): three choices, kept
 * on this device, Quality unless the person chose otherwise, and the only thing the choice changes: Gemini's thinking level.
 */
describe('the AI speed and cost setting', () => {
  beforeEach(() => localStorage.clear());

  it('offers Quality, Balanced and Economy, in that order, and Quality is the default', () => {
    expect([...AI_QUALITIES]).toEqual(['quality', 'balanced', 'economy']);
    expect(DEFAULT_AI_QUALITY).toBe('quality');
  });

  it('maps the choices to Gemini\'s thinking level: Quality sends none, Balanced MEDIUM, Economy LOW', () => {
    expect(geminiThinkingLevel('quality')).toBeNull();
    expect(geminiThinkingLevel('balanced')).toBe('MEDIUM');
    expect(geminiThinkingLevel('economy')).toBe('LOW');
  });

  it('reads nothing saved as Quality', () => {
    expect(readAiQuality()).toBe('quality');
  });

  it('keeps a valid choice under a doorprints. key and reads it back', () => {
    expect(AI_QUALITY_KEY.startsWith(STORAGE_PREFIX)).toBe(true);
    for (const q of AI_QUALITIES) {
      expect(saveAiQuality(q)).toBe(q);
      expect(localStorage.getItem(AI_QUALITY_KEY)).toBe(q);
      expect(readAiQuality()).toBe(q);
    }
  });

  it('reads an unknown, empty, wrong-case or hostile stored value as Quality', () => {
    for (const bad of ['', 'cheap', 'Economy', 'ECONOMY', ' economy', 'balanced ', 'LOW', 'MEDIUM', '0', 'null', '__proto__', '{"q":"economy"}']) {
      localStorage.setItem(AI_QUALITY_KEY, bad);
      expect(readAiQuality(), bad).toBe('quality');
    }
  });

  it('parses text without touching storage, and a saved unknown value is never written', () => {
    expect(parseAiQuality('economy')).toBe('economy');
    expect(parseAiQuality('nonsense')).toBe('quality');
    expect(parseAiQuality(null)).toBe('quality');
    expect(parseAiQuality(undefined)).toBe('quality');
    expect(saveAiQuality('nonsense' as never)).toBe('quality');
    expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('quality');
  });

  it('clearing removes the saved choice', () => {
    saveAiQuality('economy');
    clearAiQuality();
    expect(localStorage.getItem(AI_QUALITY_KEY)).toBeNull();
    expect(readAiQuality()).toBe('quality');
  });

  it('works with storage blocked: reads Quality, and saving does not throw', () => {
    const real = Object.getOwnPropertyDescriptor(window, 'localStorage')!;
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new DOMException('blocked', 'SecurityError');
      },
    });
    try {
      expect(readAiQuality()).toBe('quality');
      expect(saveAiQuality('economy')).toBe('economy');
      expect(() => clearAiQuality()).not.toThrow();
    } finally {
      Object.defineProperty(window, 'localStorage', real);
    }
  });
});
