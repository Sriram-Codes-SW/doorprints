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

package app.doorprints.server.house;

import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The answer to a first PUT is the row every later read returns (S4b-BL-163): the database keeps microseconds, so the
 * times of a new house are cut to microseconds before they are stored and answered, as the record path does. Without
 * it a retried identical PUT came back with a different {@code createdAt} (the first answer had the server clock's
 * nanoseconds, the stored row only microseconds).
 */
class HouseTimesTest {

    private static final Instant NANOS = Instant.parse("2026-10-08T22:29:56.014973907Z");
    private static final Instant MICROS = Instant.parse("2026-10-08T22:29:56.014973Z");

    private static HouseService service(HouseRepository repo) {
        return new HouseService(repo, mock(VisitRepository.class), mock(PhotoRepository.class),
                mock(SyncVersions.class), new ClientClock(Clock.fixed(NANOS, ZoneOffset.UTC), 300, 365),
                mock(ApplicationEventPublisher.class));
    }

    private static HouseDto dto(String json) throws Exception {
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build()
                .readValue(json, HouseDto.class);
    }

    private static HouseRepository emptyRepo() {
        var repo = mock(HouseRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).then(returnsFirstArg());
        return repo;
    }

    @Test
    void aCreatedHouseTimedByTheServerClockAnswersWithMicroseconds() throws Exception {
        // No createdAt or updatedAt in the body: the server's own time, which has nanoseconds on Linux.
        var answer = service(emptyRepo()).upsert(UUID.randomUUID(),
                dto("{\"label\":\"Green View\",\"lat\":12.97,\"lon\":77.59}"));

        assertThat(answer.createdAt()).isEqualTo(MICROS);
        assertThat(answer.updatedAt()).isEqualTo(MICROS);
    }

    @Test
    void aClientStampWithNanosecondsIsKeptToMicroseconds() throws Exception {
        var answer = service(emptyRepo()).upsert(UUID.randomUUID(), dto("""
                {"label":"Green View","lat":12.97,"lon":77.59,
                 "createdAt":"2026-10-08T22:29:56.014973907Z","updatedAt":"2026-10-08T22:29:56.014973907Z"}
                """));

        assertThat(answer.createdAt()).isEqualTo(MICROS);
        assertThat(answer.updatedAt()).isEqualTo(MICROS);
    }
}
