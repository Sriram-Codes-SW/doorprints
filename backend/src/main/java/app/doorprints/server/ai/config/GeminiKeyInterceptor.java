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

package app.doorprints.server.ai.config;

import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Puts the Gemini key in use now on every chat request (docs/03 §12.1, ADR-25): the key the owner set on the owner page,
 * or else {@code AI_API_KEY}. Gemini's OpenAI-compatible endpoint reads it as a bearer token.
 */
public final class GeminiKeyInterceptor implements Interceptor {

    private final Supplier<Optional<String>> key;

    public GeminiKeyInterceptor(Supplier<Optional<String>> key) {
        this.key = key;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        var current = key.get();
        if (current.isEmpty()) return chain.proceed(request);
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer " + current.get()).build());
    }
}
