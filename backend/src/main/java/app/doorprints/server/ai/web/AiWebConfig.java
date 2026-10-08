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

import app.doorprints.server.ai.config.AiProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Always registered (it is a cheap no-op for non-AI paths) so the limit also protects /mcp when only MCP is on.
 * Order 3 = after CORS (0), the general rate limit (1) and the API-key filter (2), see WebConfig.
 */
@Configuration
public class AiWebConfig {

    /**
     * Builds the AI and MCP limiters from the configured rates and registers the filter at order 3.
     */
    @Bean
    public FilterRegistrationBean<AiRateLimitFilter> aiRateLimitFilter(AiProperties props) {
        var rl = props.rateLimit();
        var ai = new TokenBucketRateLimiter(rl.burst(), rl.requestsPerMinute());
        var mcp = new TokenBucketRateLimiter(rl.mcpRequestsPerMinute(), rl.mcpRequestsPerMinute());
        var bean = new FilterRegistrationBean<>(new AiRateLimitFilter(ai, mcp));
        bean.setOrder(3);
        return bean;
    }
}
