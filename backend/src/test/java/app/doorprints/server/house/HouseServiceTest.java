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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The blast-radius invariant on the server's soft-delete path (S4b-BL-287; docs/03 18.12, docs/02 T-D15), without a
 * database: {@code DELETE /api/candidates/{id}} is {@link HouseService#delete}, and the undo and the restore of the
 * apps reach the server as a {@link HouseService#upsert} of the live candidate with a newer stamp. Each case of
 * {@code docs/schemas/delete-scope-vectors.json} seeds rows in an in-memory repository, runs its {@code delete},
 * {@code undo} and {@code restore} steps, and the test asserts the expected rows and that every row in
 * {@code unchanged} is equal as the DTO a client pulls (so its sync version is the same too), that the photos, the
 * visits and the search index are told about the one candidate only. The cases of the apps' own lists (the
 * list per space, {@code deleteSpace}, a restore of a row that is not deleted) are rules of the apps' local stores: the
 * server has no list and no bulk action but {@code DELETE /api/data}, the one global action
 * ({@code DataServiceDeleteAllTest}). {@code dirty} is a flag of the apps' stores and is not read here. Read by the
 * website's {@code delete-scope-vectors.spec.ts} and the phones' {@code DeleteScopeVectorsTest} too.
 */
class HouseServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SERVER_OPS = List.of("delete", "undo", "restore");

    private static JsonNode vectors() throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve("docs/schemas/delete-scope-vectors.json");
            if (Files.isRegularFile(file)) return JSON.readTree(Files.readString(file));
        }
        throw new IllegalStateException("docs/schemas/delete-scope-vectors.json not found");
    }

    private static UUID uuid(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes());
    }

    /** A clock the test sets to each step's {@code at}. */
    private static final class StepClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** The service on a repository kept in a map, and the calls its collaborators got. */
    private static final class Rig {
        final Map<UUID, House> rows = new HashMap<>();
        final List<UUID> photoTombstones = new ArrayList<>();
        final List<UUID> visitUnlinks = new ArrayList<>();
        final List<UUID> events = new ArrayList<>();
        final StepClock clock = new StepClock();
        final HouseService service;

        Rig() {
            var repo = mock(HouseRepository.class);
            when(repo.findById(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(rows.get(i.<UUID>getArgument(0))));
            when(repo.save(any(House.class))).thenAnswer(i -> {
                House h = i.getArgument(0);
                rows.put(h.getId(), h);
                return h;
            });
            var photos = mock(PhotoRepository.class);
            when(photos.tombstoneAllOfHouse(any(UUID.class), any(Instant.class), anyLong())).thenAnswer(i -> {
                photoTombstones.add(i.getArgument(0));
                return 0;
            });
            var visits = mock(VisitRepository.class);
            when(visits.unlinkHouse(any(UUID.class), any(Instant.class), anyLong())).thenAnswer(i -> {
                visitUnlinks.add(i.getArgument(0));
                return 0;
            });
            var versions = mock(SyncVersions.class);
            var next = new AtomicLong(1000);
            when(versions.next()).thenAnswer(i -> next.incrementAndGet());
            ApplicationEventPublisher publisher = event -> events.add(((HouseChangedEvent) event).houseId());
            service = new HouseService(repo, visits, photos, versions, new ClientClock(clock, 300, 3650), publisher);
        }

        void seed(JsonNode row) {
            var h = new House(uuid(row.get("id").asString()));
            h.setKind(row.has("kind") ? row.get("kind").asString() : null);
            h.setLabel(row.get("label").asString());
            h.setUpdatedAt(Instant.parse(row.get("updatedAt").asString()));
            h.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
            h.setSyncVersion(rows.size() + 1);
            if (row.get("deleted").asBoolean()) h.purgeContent();
            h.setDeleted(row.get("deleted").asBoolean());
            rows.put(h.getId(), h);
        }

        Map<UUID, HouseDto> snapshot() {
            var out = new HashMap<UUID, HouseDto>();
            rows.forEach((id, h) -> out.put(id, HouseDto.from(h)));
            return out;
        }
    }

    @TestFactory
    Stream<DynamicTest> theServersDeleteAndUndoPathLeavesEveryOtherRowByteForByteTheSame() throws Exception {
        var all = new ArrayList<JsonNode>();
        vectors().get("cases").forEach(all::add);
        var cases = all.stream().filter(HouseServiceTest::isServerCase).toList();
        assertThat(cases).as("the delete, undo and restore cases are read").hasSizeGreaterThanOrEqualTo(5);
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.get("name").asString(), () -> run(c)));
    }

    /**
     * A case the server's path can run: every step is a delete, undo or restore (a restore of a row that is not deleted
     * is the apps' own no-op rule: the server would take the pushed row as a newer edit).
     */
    private static boolean isServerCase(JsonNode c) {
        if (c.get("steps").isEmpty()) return false;
        for (var step : c.get("steps")) {
            var op = step.get("op").asString();
            if (!SERVER_OPS.contains(op)) return false;
            if (op.equals("restore")) {
                boolean deleted = false;
                for (var row : c.get("seed")) {
                    if (row.get("id").asString().equals(step.get("id").asString())) deleted = row.get("deleted").asBoolean();
                }
                if (!deleted) return false;
            }
        }
        return true;
    }

    private static void run(JsonNode c) {
        var rig = new Rig();
        c.get("seed").forEach(rig::seed);
        var before = rig.snapshot();
        var touched = new java.util.HashSet<UUID>();
        for (var step : c.get("steps")) {
            var op = step.get("op").asString();
            var target = uuid(step.get("id").asString());
            var at = Instant.parse(step.get("at").asString());
            touched.add(target);
            rig.clock.now = at;
            if (op.equals("delete")) {
                rig.service.delete(target);
            } else {
                // The apps stamp an undo or a restore past the tombstone (max(at, stamp + 1 ms)) and push the live row.
                var stored = rig.rows.get(target);
                var stamp = at.isAfter(stored.getUpdatedAt()) ? at : stored.getUpdatedAt().plusMillis(1);
                var live = new House(target);
                live.setKind(stored.getKind());
                live.setLabel(step.get("id").asString());
                live.setUpdatedAt(stamp);
                live.setCreatedAt(stored.getCreatedAt());
                rig.service.upsert(target, HouseDto.from(live));
            }
        }
        var after = rig.snapshot();
        assertThat(after.keySet()).as("no row is added or removed").isEqualTo(before.keySet());
        for (var expected : c.get("expect").get("rows")) {
            var dto = after.get(uuid(expected.get("id").asString()));
            var name = expected.get("id").asString();
            assertThat(dto.kind()).as(name + " kind").isEqualTo(expected.get("kind").asString());
            assertThat(dto.deleted()).as(name + " deleted").isEqualTo(expected.get("deleted").asBoolean());
            assertThat(dto.updatedAt()).as(name + " updatedAt").isEqualTo(Instant.parse(expected.get("updatedAt").asString()));
        }
        for (var id : c.get("expect").get("unchanged")) {
            var key = uuid(id.asString());
            assertThat(after.get(key)).as(id.asString() + " is byte-identical").isEqualTo(before.get(key));
        }
        // Nothing else is read off the row set: every row outside the steps is the same, and the side effects name only the targets.
        before.keySet().stream().filter(k -> !touched.contains(k))
                .forEach(k -> assertThat(after.get(k)).isEqualTo(before.get(k)));
        assertThat(rig.photoTombstones).as("photos tombstoned for the deleted candidate only").allMatch(touched::contains);
        assertThat(rig.visitUnlinks).as("visits unlinked for the deleted candidate only").allMatch(touched::contains);
        assertThat(rig.events).as("the search index is told about the touched candidates only").allMatch(touched::contains);
    }
}
