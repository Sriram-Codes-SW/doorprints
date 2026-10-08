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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The configured caps reach the service (S4b-BL-191, docs/06 TC-I-50): with 3 open in all and 2 per source, the
 * service refuses at those numbers, not at the defaults (50 and 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.pairing.max-open=3", "app.pairing.max-per-source=2"})
@ResourceLock("database")
class PairingConfiguredCapsIntegrationTest {

    /** Generated per run, never a literal (nothing for secret scanners). */
    private static final String OWNER_KEY = "it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> OWNER_KEY);
    }

    @Autowired
    PairingService pairing;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptyTable() {
        jdbc.sql("DELETE FROM pairing_request").update();
    }

    @Test
    void theSourceCapOfTwoIsHonoured() {
        pairing.start("A", "203.0.113.1");
        pairing.start("B", "203.0.113.1");

        assertThatThrownBy(() -> pairing.start("C", "203.0.113.1")).isInstanceOf(PairingBusyException.class);
    }

    @Test
    void theTotalCapOfThreeIsHonoured() {
        pairing.start("A", "203.0.113.1");
        pairing.start("B", "203.0.113.2");
        pairing.start("C", "203.0.113.3");

        assertThatThrownBy(() -> pairing.start("D", "203.0.113.4")).isInstanceOf(PairingBusyException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM pairing_request").query(Long.class).single()).isEqualTo(3);
    }
}
