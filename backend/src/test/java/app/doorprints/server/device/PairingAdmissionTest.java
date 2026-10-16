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

import app.doorprints.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule that decides whether a new pairing request may open (S4b-BL-161, docs/03 section 12.1, docs/06 TC-S-48):
 * at most 50 open requests in all and 5 per source, the older ones never pushed out, and the caller told how long to
 * wait. The numbers are the stated caps, written out here, not read from the code under test.
 */
class PairingAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private static PairingAdmission.Open open(int total, int firstExpiresInSeconds, int fromClient,
                                              int clientFirstExpiresInSeconds) {
        return new PairingAdmission.Open(total, total == 0 ? null : NOW.plusSeconds(firstExpiresInSeconds), fromClient,
                fromClient == 0 ? null : NOW.plusSeconds(clientFirstExpiresInSeconds));
    }

    /** The caps in force with nothing set: 50 open in all, 5 per source (written out, not read from the code). */
    private static OptionalLong retry(PairingAdmission.Open open) {
        return PairingAdmission.retryAfterSeconds(open, 50, 5, NOW);
    }

    @Test
    void theDefaultCapsAreFiftyInAllAndFivePerSource() {
        var unset = new AppProperties.Pairing(null, null, null, null);
        assertThat(unset.maxOpen()).isEqualTo(50);
        assertThat(unset.maxPerSource()).isEqualTo(5);
    }

    @Test
    void aConfiguredCapIsHonouredNotTheDefault() {
        // Total cap 3: three open refuse, two admit (the default 50 would admit both).
        assertThat(PairingAdmission.retryAfterSeconds(open(3, 100, 1, 100), 3, 5, NOW)).hasValue(100);
        assertThat(PairingAdmission.retryAfterSeconds(open(2, 100, 1, 100), 3, 5, NOW)).isEmpty();
        // Per-source cap 2: two from this source refuse, one admits (the default 5 would admit both).
        assertThat(PairingAdmission.retryAfterSeconds(open(2, 300, 2, 80), 50, 2, NOW)).hasValue(80);
        assertThat(PairingAdmission.retryAfterSeconds(open(2, 300, 1, 80), 50, 2, NOW)).isEmpty();
    }

    @Test
    void aRaisedCapAdmitsWhereTheDefaultWouldRefuse() {
        // The API scan lifts both caps to a million: 50 open and 5 from one source are no longer a reason to refuse.
        assertThat(PairingAdmission.retryAfterSeconds(open(50, 100, 5, 100), 1_000_000, 1_000_000, NOW)).isEmpty();
        assertThat(retry(open(50, 100, 5, 100))).hasValue(100);
    }

    @Test
    void aCapBelowOneIsRefusedAtStartupWithAClearMessage() {
        assertThatThrownBy(() -> new AppProperties.Pairing(null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.pairing.max-open").hasMessageContaining("PAIRING_MAX_OPEN")
                .hasMessageContaining("at least 1");
        assertThatThrownBy(() -> new AppProperties.Pairing(null, null, null, -3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.pairing.max-per-source").hasMessageContaining("PAIRING_MAX_PER_SOURCE");
    }

    @Test
    void nothingOpenAdmits() {
        assertThat(retry(open(0, 0, 0, 0))).isEmpty();
    }

    @Test
    void justUnderBothCapsAdmits() {
        assertThat(retry(open(49, 300, 4, 300))).isEmpty();
    }

    @Test
    void theFiftiethOpenRequestRefusesTheNextStartAndNamesTheWaitUntilTheOldestExpires() {
        assertThat(retry(open(50, 120, 1, 120))).hasValue(120);
    }

    @Test
    void aSourceAtItsFiveRefusesEvenWhenTheTableHasRoom() {
        assertThat(retry(open(5, 400, 5, 90))).hasValue(90);
    }

    @Test
    void theSourceCapWaitsForThatSourcesOldestNotTheTablesOldest() {
        // The table's oldest open request (30 s) belongs to someone else; this source's own frees up in 500 s.
        assertThat(retry(open(6, 30, 5, 500))).hasValue(500);
    }

    @Test
    void bothCapsHitWaitsForTheLaterOfTheTwo() {
        assertThat(retry(open(50, 20, 5, 200))).hasValue(200);
        assertThat(retry(open(50, 300, 5, 40))).hasValue(300);
    }

    @Test
    void aPartOfASecondRoundsUpAndTheWaitIsNeverLessThanOne() {
        var halfASecondLeft = new PairingAdmission.Open(50, NOW.plusMillis(1500), 1, NOW.plusMillis(1500));
        assertThat(retry(halfASecondLeft)).hasValue(2);
        var alreadyDue = new PairingAdmission.Open(50, NOW.minusSeconds(3), 1, NOW.minusSeconds(3));
        assertThat(retry(alreadyDue)).hasValue(1);
    }
}
