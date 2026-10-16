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

package app.doorprints.server.backup;

import app.doorprints.server.backup.ImportReport.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import's side of the tie rule (S4b-BL-163; docs/11 section 5.2): a file row with the same {@code updatedAt} as
 * the stored one is the same version, so nothing is written and it is reported UNCHANGED. The sync push now agrees
 * for the same content (it writes nothing and burns no version); for other content under the same stamp the sync
 * overwrites and the import does not look (documented in docs/03 section 10.1).
 */
class ImportTieRuleTest {

    private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void aStampEqualToTheStoredOneIsUnchanged() {
        assertThat(ImportReport.decide(T, T)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aNewerFileRowUpdatesAnOlderOneKeepsAndAMissingOneCreates() {
        assertThat(ImportReport.decide(T.minusMillis(1), T)).isEqualTo(Outcome.UPDATED);
        assertThat(ImportReport.decide(T.plusMillis(1), T)).isEqualTo(Outcome.KEPT_NEWER);
        assertThat(ImportReport.decide(null, T)).isEqualTo(Outcome.CREATED);
    }
}
