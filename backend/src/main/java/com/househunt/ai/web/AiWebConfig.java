package com.househunt.ai.web;

import com.househunt.ai.config.AiProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Always registered (it is a cheap no-op for non-AI paths) so the limit also protects /mcp when only MCP is on.
 * Order 3 = after CORS (0), the general rate limit (1) and the API-key filter (2), see WebConfig.
 */
@Configuration
public class AiWebConfig {

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
