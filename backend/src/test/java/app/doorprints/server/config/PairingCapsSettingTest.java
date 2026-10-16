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

package app.doorprints.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caps on open pairing requests as the operator sets them: {@code PAIRING_MAX_OPEN} and
 * {@code PAIRING_MAX_PER_SOURCE} read through the real {@code application.yml} (S4b-BL-161, S4b-BL-191; docs/06
 * TC-S-49). The defaults are 50 and 5; the release gate's API scan sets both to a million.
 */
class PairingCapsSettingTest {

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {
    }

    private ApplicationContextRunner runner(String... systemProperties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .withSystemProperties(systemProperties);
    }

    @Test
    void fiftyInAllAndFivePerSourceWhenNothingIsSet() {
        runner().run(ctx -> {
            var pairing = ctx.getBean(AppProperties.class).pairing();
            assertThat(pairing.maxOpen()).isEqualTo(50);
            assertThat(pairing.maxPerSource()).isEqualTo(5);
        });
    }

    @Test
    void emptyVariablesLeaveTheDefaults() {
        runner("PAIRING_MAX_OPEN=", "PAIRING_MAX_PER_SOURCE=").run(ctx -> {
            var pairing = ctx.getBean(AppProperties.class).pairing();
            assertThat(pairing.maxOpen()).isEqualTo(50);
            assertThat(pairing.maxPerSource()).isEqualTo(5);
        });
    }

    @Test
    void theVariablesSetTheCaps() {
        runner("PAIRING_MAX_OPEN=1000000", "PAIRING_MAX_PER_SOURCE=7").run(ctx -> {
            var pairing = ctx.getBean(AppProperties.class).pairing();
            assertThat(pairing.maxOpen()).isEqualTo(1_000_000);
            assertThat(pairing.maxPerSource()).isEqualTo(7);
        });
    }

    @Test
    void zeroStopsStartupAndTheMessageNamesTheVariable() {
        runner("PAIRING_MAX_PER_SOURCE=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("PAIRING_MAX_PER_SOURCE")
                    .hasStackTraceContaining("at least 1");
        });
    }
}
