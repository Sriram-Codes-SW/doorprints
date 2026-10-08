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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;

/** Plain switches the owner sets on the owner page (docs/03 §12.1), such as pausing AI for the whole server. */
@Component
public class ServerSettings {

    public static final String AI_PAUSED = "ai_paused";

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Boolean aiPaused;

    public ServerSettings(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<String> get(String name) {
        return jdbc.sql("SELECT value FROM server_setting WHERE name = :name").param("name", name)
                .query(String.class).optional();
    }

    /**
     * Stores or replaces a setting; the AI-paused switch is also updated in memory at once.
     */
    public void put(String name, String value) {
        jdbc.sql("""
                        INSERT INTO server_setting (name, value, updated_at) VALUES (:name, :value, :now)
                        ON CONFLICT (name) DO UPDATE SET value = :value, updated_at = :now""")
                .param("name", name).param("value", value).param("now", Timestamp.from(clock.instant())).update();
        if (AI_PAUSED.equals(name)) aiPaused = Boolean.valueOf(value);
    }

    /** The owner paused AI for the whole server (every device and the owner key). Read once, then kept in memory. */
    public boolean aiPaused() {
        var cached = aiPaused;
        if (cached == null) {
            cached = get(AI_PAUSED).map(Boolean::valueOf).orElse(false);
            aiPaused = cached;
        }
        return cached;
    }
}
