package com.househunt.ai.web;

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
