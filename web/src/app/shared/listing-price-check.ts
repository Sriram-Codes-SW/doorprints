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
import type { HouseDraft } from '../core/ai.service';
import { parseListingText } from './listing-text';

/** The two readings of the price when they disagree: what the no-AI parser found in the text, what the model returned. */
export interface PriceDisagreement {
  text: number;
  ai: number;
}

/**
 * The zero-cost check on the one high-stakes field (S4b-BL-238, docs/ai/ai-design.md 8.6): the no-AI parser's price
 * (`parseListingText`, the same regex the share sheet uses, the phones' `ListingText.parse`) against the model's. A
 * disagreement is a warning for the person to read, never a correction: the model may be right (the parser takes the
 * first rupee amount, which may be the deposit) and the person decides. Null when either side has no price or both agree.
 */
export function priceDisagreement(text: string, draft: Pick<HouseDraft, 'price'>): PriceDisagreement | null {
  if (draft.price === null || draft.price === undefined || !text.trim()) return null;
  const parsed = parseListingText(text).price;
  if (parsed === null || parsed === draft.price) return null;
  return { text: parsed, ai: draft.price };
}
