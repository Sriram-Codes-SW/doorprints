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

package app.doorprints.server.ai.mcp;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the MCP server identity that clients (Claude Desktop, Cowork) see. The values live in the backend's
 * {@code application.yml}; this reads the raw file, so a silent revert of the product rename fails the build.
 * No Spring context and no database are needed.
 */
class McpServerIdentityTest {

    private static PropertySource<?> applicationYml() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml documents").isNotEmpty();
        return sources.get(0);
    }

    @Test
    void serverInfoNameIsDoorprints() throws IOException {
        assertThat(applicationYml().getProperty("spring.ai.mcp.server.name")).hasToString("doorprints");
    }

    @Test
    void endpointPathStaysMcp() throws IOException {
        assertThat(applicationYml().getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint"))
                .hasToString("/mcp");
    }

    @Test
    void instructionsDoNotUseTheOldProductName() throws IOException {
        Object instructions = applicationYml().getProperty("spring.ai.mcp.server.instructions");
        assertThat(instructions).isNotNull();
        assertThat(instructions.toString())
                .doesNotContain("House Hunt")
                .doesNotContain("house-hunt")
                .contains("[contact]");
    }
}
