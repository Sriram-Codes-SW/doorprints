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

import app.doorprints.server.ai.agent.HouseQueries;
import app.doorprints.server.ai.rag.RagService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feature 4: MCP server. Spring AI's MCP server starter (WebMVC, Streamable HTTP at /mcp, see application.yml)
 * turns every {@link ToolCallbackProvider} bean into MCP tools. This is the ONLY such bean in the app, so the agent's
 * route tools are not exposed. /mcp is protected by the same API key filter and rate limit as /api/ai/**.
 */
@Configuration
@ConditionalOnBooleanProperty("app.mcp.enabled")
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider doorprintsMcpTools(HouseQueries queries, ObjectProvider<RagService> rag) {
        return MethodToolCallbackProvider.builder().toolObjects(new McpHouseTools(queries, rag)).build();
    }
}
