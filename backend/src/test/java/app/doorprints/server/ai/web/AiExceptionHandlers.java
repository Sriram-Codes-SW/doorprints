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
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

/** Builds an {@link AiExceptionHandler} for a test, with the settings or, for {@code null}, without any. */
public final class AiExceptionHandlers {

    private AiExceptionHandlers() {
    }

    public static AiExceptionHandler of(AiProperties props) {
        var beans = props == null ? Map.<String, Object>of() : Map.<String, Object>of("aiProperties", props);
        return new AiExceptionHandler(new StaticListableBeanFactory(beans).getBeanProvider(AiProperties.class));
    }
}
