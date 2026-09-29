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

package app.doorprints.server.device;

import app.doorprints.server.ai.web.TokenBucketRateLimiter;
import app.doorprints.server.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wiring for device pairing and the owner page (docs/03 §12.1, ADR-25). */
@Configuration
public class DeviceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** After the general per-address limit (1), before the key check (2). */
    @Bean
    public FilterRegistrationBean<PairingRateLimitFilter> pairingRateLimitFilter(AppProperties props) {
        var p = props.pairing();
        var bean = new FilterRegistrationBean<>(new PairingRateLimitFilter(
                new TokenBucketRateLimiter(p.burst(), p.requestsPerMinute())));
        bean.setOrder(1);
        return bean;
    }

    /** Right after the key check, which marks device keys, and before the AI limits. */
    @Bean
    public FilterRegistrationBean<OwnerFilter> ownerFilter(OwnerAuth auth) {
        var bean = new FilterRegistrationBean<>(new OwnerFilter(auth));
        bean.setOrder(2);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<DeviceAiGuard> deviceAiGuard(app.doorprints.server.secrets.ServerSettings settings) {
        var bean = new FilterRegistrationBean<>(new DeviceAiGuard(settings::aiPaused));
        bean.setOrder(3);
        return bean;
    }
}
