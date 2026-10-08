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

package app.doorprints.server.common;

import app.doorprints.server.record.RecordController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusals the server writes for its callers still arrive with their text (S4b-BL-159). {@code ApiExceptionHandler}
 * answers every {@code IllegalArgumentException} that is not a {@link BadRequestException} with a fixed "Malformed
 * request", so a refusal thrown as a plain one would lose its message; this class asks over HTTP for each refusal the
 * sync and record endpoints make and reads the answer. The backup importer's refusals have their texts asserted in
 * {@code BackupApiTest}, a photo's metadata stamp in {@code PhotoMetaApiTest}.
 *
 * <p>Runs against the shared PostGIS database like the other integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.requests-per-minute=60000", "app.rate-limit.burst=6000"})
@ResourceLock("database")
class ClientErrorTextTest {

    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); >= 32 chars. */
    private static final String KEY = "client-errors-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    private RestClient api() {
        return RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
    }

    /** The 400 answer's body, or a failure when the call did not end in a 400. */
    private static String refusal(Supplier<?> call) {
        try {
            call.get();
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            return e.getResponseBodyAsString();
        }
        throw new AssertionError("expected a 400");
    }

    private String putVisit(Map<String, Object> body) {
        return refusal(() -> api().put().uri("/api/visits/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
    }

    private String putRecord(String type, String id, String json) {
        return refusal(() -> api().put().uri("/api/records/{type}/{id}", type, id)
                .contentType(MediaType.APPLICATION_JSON).body(json).retrieve().body(String.class));
    }

    @Test
    void aVisitSaysWhichTimeIsWrong() {
        var back = Map.<String, Object>of("lat", 12.9, "lon", 77.6, "arrivedAt", "2026-10-01T10:00:00Z",
                "leftAt", "2026-10-01T09:00:00Z");
        assertThat(putVisit(back)).contains("leftAt must not be before arrivedAt");
        var ancient = Map.<String, Object>of("lat", 12.9, "lon", 77.6, "arrivedAt", "1970-01-02T00:00:00Z");
        assertThat(putVisit(ancient)).contains("arrivedAt is out of range (check the device clock)");
        var absurdStamp = Map.<String, Object>of("lat", 12.9, "lon", 77.6, "arrivedAt", "2026-10-01T10:00:00Z",
                "updatedAt", "2200-01-01T00:00:00Z");
        assertThat(putVisit(absurdStamp)).contains("updatedAt is out of range (check the device clock)");
    }

    @Test
    void aRecordSaysWhatIsWrongWithIt() {
        assertThat(putRecord("broker", "b1", "{\"type\":\"broker\",\"id\":\"b2\",\"payload\":{}}"))
                .contains("type and id in the path and in the body must agree");
        assertThat(putRecord("broker", "b1", "{\"type\":\"broker\",\"id\":\"b1\",\"payload\":[1]}"))
                .contains("payload must be a JSON object");
        assertThat(putRecord("broker", "b1", "{\"type\":\"broker\",\"id\":\"b1\",\"payload\":{\"x\":\""
                + "x".repeat(RecordController.MAX_PAYLOAD_BYTES) + "\"}}")).contains("payload too large");
    }

    @Test
    void aFileThatIsNotAnImageSaysSo() {
        var houseId = UUID.randomUUID();
        api().put().uri("/api/houses/{id}", houseId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("label", "Photo target", "lat", 12.9, "lon", 77.6)).retrieve().toBodilessEntity();
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("id", UUID.randomUUID().toString());
        form.add("file", new ByteArrayResource("<svg onload=alert(1)>".getBytes()) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        assertThat(refusal(() -> api().post().uri("/api/houses/{id}/photos", houseId)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class)))
                .contains("Only well-formed JPEG, PNG or WebP images are allowed");
    }
}
