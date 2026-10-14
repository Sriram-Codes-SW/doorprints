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

import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.record.RecordRepository;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code DELETE /api/data} is the server's one global delete (S4b-BL-287; docs/02 T-D15): it is not a soft delete and
 * has no scope, so it is pinned as what it is, a table-wide hard delete of every candidate kind, its visits, photos,
 * records and the AI index, in the order that satisfies the foreign keys. The soft-delete path of one candidate is
 * {@code HouseServiceTest}.
 */
class DataServiceDeleteAllTest {

    @Test
    void deleteAllHardDeletesEveryTableWholeAndNeverBySpace() {
        var statements = new ArrayList<String>();
        var em = mock(EntityManager.class);
        var query = mock(Query.class);
        when(query.executeUpdate()).thenReturn(0);
        when(query.getSingleResult()).thenReturn(Boolean.TRUE);
        when(em.createNativeQuery(anyString())).thenAnswer(i -> {
            statements.add(i.getArgument(0));
            return query;
        });
        var props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.privacy().tombstoneRetentionDays()).thenReturn(90);
        var service = new DataService(mock(HouseRepository.class), mock(VisitRepository.class),
                mock(PhotoRepository.class), mock(RecordRepository.class), mock(SyncVersions.class), props,
                Clock.systemUTC());
        ReflectionTestUtils.setField(service, "em", em);

        service.deleteAll();

        List<String> deletes = statements.stream().filter(s -> s.startsWith("delete from")).toList();
        assertThat(deletes).containsExactly("delete from photo", "delete from visit", "delete from house_checklist",
                "delete from house", "delete from record", "delete from vector_store");
        assertThat(deletes).as("no where clause: it is not scoped to a kind").noneMatch(s -> s.contains("where"));
    }
}
