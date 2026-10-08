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

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void theCapsAreFiftyInAllAndFivePerSource() {
        assertThat(PairingAdmission.MAX_OPEN).isEqualTo(50);
        assertThat(PairingAdmission.MAX_OPEN_PER_CLIENT).isEqualTo(5);
    }

    @Test
    void nothingOpenAdmits() {
        assertThat(PairingAdmission.retryAfterSeconds(open(0, 0, 0, 0), NOW)).isEmpty();
    }

    @Test
    void justUnderBothCapsAdmits() {
        assertThat(PairingAdmission.retryAfterSeconds(open(49, 300, 4, 300), NOW)).isEmpty();
    }

    @Test
    void theFiftiethOpenRequestRefusesTheNextStartAndNamesTheWaitUntilTheOldestExpires() {
        assertThat(PairingAdmission.retryAfterSeconds(open(50, 120, 1, 120), NOW)).hasValue(120);
    }

    @Test
    void aSourceAtItsFiveRefusesEvenWhenTheTableHasRoom() {
        assertThat(PairingAdmission.retryAfterSeconds(open(5, 400, 5, 90), NOW)).hasValue(90);
    }

    @Test
    void theSourceCapWaitsForThatSourcesOldestNotTheTablesOldest() {
        // The table's oldest open request (30 s) belongs to someone else; this source's own frees up in 500 s.
        assertThat(PairingAdmission.retryAfterSeconds(open(6, 30, 5, 500), NOW)).hasValue(500);
    }

    @Test
    void bothCapsHitWaitsForTheLaterOfTheTwo() {
        assertThat(PairingAdmission.retryAfterSeconds(open(50, 20, 5, 200), NOW)).hasValue(200);
        assertThat(PairingAdmission.retryAfterSeconds(open(50, 300, 5, 40), NOW)).hasValue(300);
    }

    @Test
    void aPartOfASecondRoundsUpAndTheWaitIsNeverLessThanOne() {
        var halfASecondLeft = new PairingAdmission.Open(50, NOW.plusMillis(1500), 1, NOW.plusMillis(1500));
        assertThat(PairingAdmission.retryAfterSeconds(halfASecondLeft, NOW)).hasValue(2);
        var alreadyDue = new PairingAdmission.Open(50, NOW.minusSeconds(3), 1, NOW.minusSeconds(3));
        assertThat(PairingAdmission.retryAfterSeconds(alreadyDue, NOW)).hasValue(1);
    }
}
