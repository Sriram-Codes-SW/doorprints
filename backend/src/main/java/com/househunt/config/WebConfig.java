package com.househunt.config;

import com.househunt.ai.web.TokenBucketRateLimiter;
import com.househunt.backup.BackupController;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Servlet filter chain, in order:
 * <ol>
 *   <li>-100 {@link SecurityHeadersFilter}: headers on every response, including errors written by later filters</li>
 *   <li>-90 {@link RequestSizeLimitFilter}: JSON body cap (413), larger for POST /api/import</li>
 *   <li>0 CORS: so 401/429 responses still carry CORS headers the browser can read</li>
 *   <li>1 {@link ApiRateLimitFilter}: per-address flood limit (429)</li>
 *   <li>2 {@link ApiKeyFilter}: canonical path check (400), then deny-by-default key check (401/429)</li>
 *   <li>3 AiRateLimitFilter (see AiWebConfig): LLM quota protection for /api/ai/** and /mcp</li>
 * </ol>
 */
@Configuration
@EnableScheduling
public class WebConfig {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        // Fail fast with a clear message (F-01): APP_API_KEY >= 32 chars, APP_API_KEY_NEXT empty or >= 32 chars.
        ApiKeyFilter.validateKeys(props.apiKey(), props.apiKeyNext());
        this.props = props;
    }

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        var bean = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        bean.setOrder(-100);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        var limits = props.limits();
        var bean = new FilterRegistrationBean<>(new RequestSizeLimitFilter(
                limits.maxJsonBytes(), BackupController.IMPORT_PATH, limits.maxImportBytes()));
        bean.setOrder(-90);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsOrigins() == null ? List.of() : props.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("X-API-Key", "Content-Type", "Authorization", "X-Confirm-Delete"));
        cors.setExposedHeaders(List.of("Retry-After", "Content-Disposition"));
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        var bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(0);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilter() {
        var rl = props.rateLimit();
        var bean = new FilterRegistrationBean<>(
                new ApiRateLimitFilter(new TokenBucketRateLimiter(rl.burst(), rl.requestsPerMinute())));
        bean.setOrder(1);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter() {
        var rl = props.rateLimit();
        var failures = new TokenBucketRateLimiter(rl.authFailureBurst(), rl.authFailuresPerMinute());
        var bean = new FilterRegistrationBean<>(new ApiKeyFilter(props.apiKey(), props.apiKeyNext(), failures));
        bean.setOrder(2);
        return bean;
    }
}
