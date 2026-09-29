package app.doorprints.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes the API's OpenAPI description to {@code target/openapi.json} for the release security gate's ZAP API scan
 * (S4b-SEC-1, docs/06 TC-S-04; the {@code image} job of backend.yml). springdoc is a test-scope dependency only, so
 * the jar and the image never serve {@code /v3/api-docs}. The description also sits behind the API key here, like
 * every other path outside the health allowlist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ResourceLock("database")
class OpenApiSpecTest {

    private static final String KEY = "openapi-" + UUID.randomUUID();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void writesTheOpenApiDescriptionOfEveryApiPath() throws Exception {
        String json = RestClient.create("http://localhost:" + port).get().uri("/v3/api-docs")
                .header("X-API-Key", KEY).retrieve().body(String.class);
        JsonNode spec = JsonMapper.builder().build().readTree(json);
        JsonNode paths = spec.path("paths");
        // The scan must see the whole API: the houses, sync, visits, photos, backup and data paths.
        assertThat(paths.propertyNames()).contains("/api/houses", "/api/stats", "/api/data");
        assertThat(paths.size()).isGreaterThan(10);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "openapi.json"), json);
    }

    @Test
    void theDescriptionNeedsTheKeyLikeTheRestOfTheApi() {
        var status = RestClient.create("http://localhost:" + port).get().uri("/v3/api-docs")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }
}
