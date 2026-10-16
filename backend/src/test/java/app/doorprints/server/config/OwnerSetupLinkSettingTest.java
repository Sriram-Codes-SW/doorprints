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
 * The owner's recovery switch as the operator sets it: the variable {@code OWNER_SETUP_LINK_IN_LOG} read through the
 * real {@code application.yml} (S4b-BL-188, docs/03 section 12.1, docs/06 TC-S-47). Off unless turned on.
 */
class OwnerSetupLinkSettingTest {

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
    void offWhenTheVariableIsNotSet() {
        runner().run(ctx -> assertThat(ctx.getBean(AppProperties.class).owner().setupLinkInLog()).isFalse());
    }

    @Test
    void offWhenTheVariableIsEmpty() {
        runner("OWNER_SETUP_LINK_IN_LOG=")
                .run(ctx -> assertThat(ctx.getBean(AppProperties.class).owner().setupLinkInLog()).isFalse());
    }

    @Test
    void onOnlyWhenTheVariableIsTrue() {
        runner("OWNER_SETUP_LINK_IN_LOG=true")
                .run(ctx -> assertThat(ctx.getBean(AppProperties.class).owner().setupLinkInLog()).isTrue());
    }
}
