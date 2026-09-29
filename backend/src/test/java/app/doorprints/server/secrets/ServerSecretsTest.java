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

package app.doorprints.server.secrets;

import app.doorprints.server.ai.config.GeminiKeyInterceptor;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The owner page's secrets and the key on AI requests (docs/03 §12.1, ADR-25; docs/06 TC-U-83). */
class ServerSecretsTest {

    private static final String OWNER = "owner-" + java.util.UUID.randomUUID();

    @Test
    void aSecretRoundTripsUnderTheOwnerKeyOnly() {
        var key = ServerSecrets.derive(OWNER);
        var sealed = ServerSecrets.seal(key, "gemini_api_key", "AIza-example-value-1234");
        assertThat(ServerSecrets.open(key, "gemini_api_key", sealed)).contains("AIza-example-value-1234");
        // Another owner key reads nothing, and the derivation is stable.
        assertThat(ServerSecrets.open(ServerSecrets.derive(OWNER + "x"), "gemini_api_key", sealed)).isEmpty();
        assertThat(ServerSecrets.open(ServerSecrets.derive(OWNER), "gemini_api_key", sealed)).isPresent();
        // The name is bound to the value: it cannot be moved under another name.
        assertThat(ServerSecrets.open(key, "other", sealed)).isEmpty();
        // A changed byte is refused.
        sealed[sealed.length - 1] ^= 1;
        assertThat(ServerSecrets.open(key, "gemini_api_key", sealed)).isEmpty();
    }

    @Test
    void eachWriteUsesAFreshNonce() {
        var key = ServerSecrets.derive(OWNER);
        assertThat(ServerSecrets.seal(key, "n", "same")).isNotEqualTo(ServerSecrets.seal(key, "n", "same"));
    }

    @Test
    void chatRequestsCarryTheKeyInUseNow() throws Exception {
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var current = new AtomicReference<Optional<String>>(Optional.of("first-key"));
            var client = new OkHttpClient.Builder().addInterceptor(new GeminiKeyInterceptor(current::get)).build();
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            var request = new Request.Builder().url(url).header("Authorization", "Bearer placeholder").build();

            client.newCall(request).execute().close();
            assertThat(seen.get()).isEqualTo("Bearer first-key");

            current.set(Optional.of("second-key"));
            client.newCall(request).execute().close();
            assertThat(seen.get()).isEqualTo("Bearer second-key");

            current.set(Optional.empty());
            client.newCall(request).execute().close();
            assertThat(seen.get()).isEqualTo("Bearer placeholder");
        } finally {
            server.stop(0);
        }
    }
}
