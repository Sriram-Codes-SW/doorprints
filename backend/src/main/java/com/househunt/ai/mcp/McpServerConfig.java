package com.househunt.ai.mcp;

import com.househunt.ai.agent.HouseQueries;
import com.househunt.ai.rag.RagService;
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
    public ToolCallbackProvider houseHuntMcpTools(HouseQueries queries, ObjectProvider<RagService> rag) {
        return MethodToolCallbackProvider.builder().toolObjects(new McpHouseTools(queries, rag)).build();
    }
}
