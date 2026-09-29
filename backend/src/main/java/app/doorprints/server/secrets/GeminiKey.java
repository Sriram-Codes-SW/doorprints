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

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The Gemini key the server uses for AI (docs/03 §12.1, ADR-25): the one the owner set on the owner page, kept
 * encrypted ({@link ServerSecrets}), or else the one in the server's settings file ({@code AI_API_KEY}). Read on every
 * AI request, so setting or removing it on the owner page takes effect at once, with no restart.
 */
@Component
public class GeminiKey {

    public static final String SECRET = "gemini_api_key";
    /** Stands in for {@code spring.ai.openai.api-key} when AI is on but no key is set yet; never sent to Google. */
    public static final String PLACEHOLDER = "doorprints-no-key-set";

    public enum Source { OWNER_PAGE, SETTINGS_FILE, NONE }

    private final ServerSecrets secrets;
    private final String fromSettings;
    private volatile Optional<String> stored;

    public GeminiKey(ServerSecrets secrets, Environment env) {
        this.secrets = secrets;
        var configured = env.getProperty("spring.ai.openai.api-key", "").strip();
        this.fromSettings = configured.equals(PLACEHOLDER) ? "" : configured;
    }

    /** The key to use now, if any. */
    public Optional<String> current() {
        var s = stored;
        if (s == null) {
            s = secrets.get(SECRET);
            stored = s;
        }
        return s.isPresent() ? s : fromSettings.isEmpty() ? Optional.empty() : Optional.of(fromSettings);
    }

    public Source source() {
        return current().isEmpty() ? Source.NONE : secrets.describe(SECRET).isPresent() ? Source.OWNER_PAGE
                : Source.SETTINGS_FILE;
    }

    /** Last four characters of the key in use, for the owner page. */
    public Optional<String> last4() {
        return current().map(k -> k.substring(Math.max(0, k.length() - 4)));
    }

    public void set(String key) {
        secrets.put(SECRET, key);
        stored = Optional.of(key);
    }

    public void remove() {
        secrets.remove(SECRET);
        stored = Optional.empty();
    }
}
