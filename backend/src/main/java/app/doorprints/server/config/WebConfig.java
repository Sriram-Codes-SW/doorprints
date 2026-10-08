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

import app.doorprints.server.ai.web.TokenBucketRateLimiter;
import app.doorprints.server.backup.BackupController;
import app.doorprints.server.device.DeviceKeyStore;
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

    /**
     * Fails startup if the API key settings are missing or too short, before any filter is built.
     */
    public WebConfig(AppProperties props) {
        // Fail fast with a clear message (F-01): APP_API_KEY >= 32 chars, APP_API_KEY_NEXT empty or >= 32 chars.
        ApiKeyFilter.validateKeys(props.apiKey(), props.apiKeyNext());
        this.props = props;
    }

    /**
     * First in the chain (order -100), so errors written by later filters carry the headers too.
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        var bean = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        bean.setOrder(-100);
        return bean;
    }

    /**
     * JSON body cap from {@code app.limits}; the backup import path gets the larger import cap.
     */
    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        var limits = props.limits();
        var bean = new FilterRegistrationBean<>(new RequestSizeLimitFilter(
                limits.maxJsonBytes(), BackupController.IMPORT_PATH, limits.maxImportBytes()));
        bean.setOrder(-90);
        return bean;
    }

    /**
     * CORS for {@code /api/**} only, for the configured origins (none when unset). Retry-After and Content-
     * Disposition are exposed so the web app can read rate-limit waits and download names.
     */
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

    /**
     * The general per-address flood limit, ahead of the key check.
     */
    @Bean
    public FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilter() {
        var rl = props.rateLimit();
        var bean = new FilterRegistrationBean<>(
                new ApiRateLimitFilter(new TokenBucketRateLimiter(rl.burst(), rl.requestsPerMinute())));
        bean.setOrder(1);
        return bean;
    }

    /**
     * The key check, with its own bucket for failed attempts and device keys looked up in the device store.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(DeviceKeyStore devices) {
        var rl = props.rateLimit();
        var failures = new TokenBucketRateLimiter(rl.authFailureBurst(), rl.authFailuresPerMinute());
        var bean = new FilterRegistrationBean<>(
                new ApiKeyFilter(props.apiKey(), props.apiKeyNext(), failures, devices::check));
        bean.setOrder(2);
        return bean;
    }
}
