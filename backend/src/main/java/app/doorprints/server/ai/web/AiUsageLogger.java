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

package app.doorprints.server.ai.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Logs model + token counts per AI call at INFO. Never logs prompt or completion text (they contain private
 * notes); Spring AI's own observations additionally publish the {@code gen_ai.client.token.usage} metric.
 */
public final class AiUsageLogger {

    private static final Logger log = LoggerFactory.getLogger(AiUsageLogger.class);

    private AiUsageLogger() {
    }

    /**
     * Logs the feature name, model, token counts and duration of one model call; the counts read "null" or "unknown"
     * when the provider does not report them.
     */
    public static void log(String feature, ChatResponse response, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        if (response == null || response.getMetadata() == null) {
            log.info("ai.call feature={} durationMs={} usage=unknown", feature, millis);
            return;
        }
        var md = response.getMetadata();
        var usage = md.getUsage();
        log.info("ai.call feature={} model={} promptTokens={} completionTokens={} totalTokens={} durationMs={}",
                feature, md.getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(), millis);
    }
}
