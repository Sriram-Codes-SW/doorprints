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

package app.doorprints.server.ai;

/**
 * The model provider's own safety system declined the text (S4b-BL-233; docs/ai/ai-design.md 15): a finish reason such as
 * {@code content_filter} or {@code SAFETY}, raised by {@link SafetyBlockAdvisor}. {@link ProviderErrors#isDeclined} finds it in
 * the cause chain, and {@code AiExceptionHandler} answers HTTP 422 {@code AI_DECLINED}, never retried. The message is fixed:
 * what the provider said is never kept, because it can echo the prompt, which holds the person's notes (SEC-016, PRV-011).
 */
public final class ProviderBlockedException extends RuntimeException {
    public ProviderBlockedException() {
        super("The AI provider declined this text");
    }
}
