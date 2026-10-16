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

package app.doorprints.server.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer budget sent to the model as {@code maxTokens} (S4b-BL-194 item 1; docs/ai/ai-design.md 10 and 11). On
 * Gemini 3.x the output limit includes the model's hidden thinking tokens, and 2,048 left no room for the JSON on
 * ask-14 and ask-16 of the golden set (the answer was cut off: {@code UnexpectedEndOfInputException}). The default is
 * 8,192 in the record and in {@code application.yml}; the numbers are written from the rule, not read from the constants.
 */
class AiOutputTokensTest {

    @Configuration
    @EnableConfigurationProperties(AiProperties.class)
    static class Config {
    }

    private ApplicationContextRunner runner(String... systemProperties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .withSystemProperties(systemProperties);
    }

    private static AiProperties withOutputTokens(Integer value) {
        return new AiProperties(false, null, null, null, null, value, null, null, null, null, null);
    }

    @Test
    void theRecordDefaultIsEightThousandOneHundredNinetyTwoTokens() {
        assertThat(AiProperties.defaults().maxOutputTokens()).isEqualTo(8192);
        assertThat(withOutputTokens(null).maxOutputTokens()).isEqualTo(8192);
    }

    @Test
    void aConfiguredValueWins() {
        assertThat(withOutputTokens(512).maxOutputTokens()).isEqualTo(512);
        assertThat(withOutputTokens(2048).maxOutputTokens()).isEqualTo(2048);
        assertThat(withOutputTokens(32768).maxOutputTokens()).isEqualTo(32768);
    }

    @Test
    void zeroAndNegativeFallBackToTheDefault() {
        assertThat(withOutputTokens(0).maxOutputTokens()).isEqualTo(8192);
        assertThat(withOutputTokens(-1).maxOutputTokens()).isEqualTo(8192);
    }

    @Test
    void theApplicationYmlDefaultAgreesWithTheRecordDefault() {
        runner().run(ctx -> {
            assertThat(ctx.getBean(AiProperties.class).maxOutputTokens()).isEqualTo(8192);
            assertThat(ctx.getBean(AiProperties.class).maxOutputTokens()).isEqualTo(AiProperties.defaults().maxOutputTokens());
        });
    }

    @Test
    void theVariableSetsTheValueAndAnEmptyOneLeavesTheDefault() {
        runner("AI_MAX_OUTPUT_TOKENS=4096")
                .run(ctx -> assertThat(ctx.getBean(AiProperties.class).maxOutputTokens()).isEqualTo(4096));
        runner("AI_MAX_OUTPUT_TOKENS=")
                .run(ctx -> assertThat(ctx.getBean(AiProperties.class).maxOutputTokens()).isEqualTo(8192));
    }
}
