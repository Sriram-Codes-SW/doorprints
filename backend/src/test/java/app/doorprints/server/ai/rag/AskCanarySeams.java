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

package app.doorprints.server.ai.rag;

import java.util.function.UnaryOperator;

/**
 * The canary suite's hand on Ask (S4b-BL-237, docs/ai/ai-design.md 8.6): test sources only, in the service's package so
 * it can reach the package-private seams of {@link RagService}. {@code AskCanarySeamsTest} proves the default path is
 * the production one.
 */
public final class AskCanarySeams {

    private AskCanarySeams() {
    }

    /** The model's {@code citedHouseIds} count as citations next to the inline ones (canary {@code no-citation-filter}). */
    public static void listedIdsCount(boolean on) {
        RagService.canaryListedIdsCount = on;
    }

    /** Rewrites the system text before it is sent (canary {@code prompt-without-rules}); null restores the default. */
    public static void systemText(UnaryOperator<String> rewrite) {
        RagService.canarySystemText = rewrite;
    }

    public static void reset() {
        RagService.canaryListedIdsCount = false;
        RagService.canarySystemText = null;
    }

    public static boolean isDefault() {
        return !RagService.canaryListedIdsCount && RagService.canarySystemText == null;
    }
}
