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

package app.doorprints.server.privacy;

import app.doorprints.server.backup.BackupService;
import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.record.RecordRepository;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export file name and the purge cut-off come from the injected clock (S4b-BL-166).
 */
class DataClockTest {

    @Test
    void theExportFileNameCarriesTheUtcDateOfTheClockNotOfTheSystem() {
        // 20:30 UTC on 5 March is already 6 March in India: the name keeps the UTC date, like the device exporters.
        var instant = Instant.parse("2026-03-05T20:30:00Z");
        var controller = new DataController(mock(DataService.class), mock(BackupService.class),
                Clock.fixed(instant, ZoneOffset.ofHoursMinutes(5, 30)));

        var response = controller.export();

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Doorprints-backup-2026-03-05.json\"");
    }

    @Test
    void thePurgeCutOffIsNinetyDaysBeforeTheClocksTime() {
        var instant = Instant.parse("2026-06-30T03:30:00Z");
        var photos = mock(PhotoRepository.class);
        var visits = mock(VisitRepository.class);
        var houses = mock(HouseRepository.class);
        var records = mock(RecordRepository.class);
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.privacy().tombstoneRetentionDays()).thenReturn(90);
        var service = new DataService(houses, visits, photos, records, mock(SyncVersions.class),
                props,
                Clock.fixed(instant, ZoneOffset.UTC));

        service.purgeTombstones();

        var cutOff = Instant.parse("2026-04-01T03:30:00Z");
        assertThat(cutOff).isEqualTo(instant.minus(Duration.ofDays(90)));
        verify(photos).purgeTombstonesBefore(cutOff);
        verify(visits).purgeTombstonesBefore(cutOff);
        verify(houses).purgeTombstonesBefore(cutOff);
        verify(records).purgeTombstonesBefore(cutOff);
    }
}
