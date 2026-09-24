package app.doorprints.server.ai.web;

import com.google.genai.errors.ClientException;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertex AI setup hints: chat points to GCP_LOCATION, embeddings to AI_VERTEX_EMBEDDING_LOCATION; quota never hints. */
class AiExceptionHandlerTest {

    private static AiProperties vertex(String location, String embeddingLocation) {
        return new AiProperties(true, AiProperties.VERTEX, null, null, null, null, null, null, null, null,
                new AiProperties.Vertex("doorprints-ai", location, embeddingLocation, null, null));
    }

    private static AiUnavailableException failure(Throwable providerError) {
        return new AiUnavailableException("Answering failed", new RuntimeException("wrapped", providerError));
    }

    private static Object hint(AiExceptionHandler handler, Throwable providerError) {
        var body = handler.aiUnavailable(failure(providerError)).getBody();
        assertThat(body).isNotNull();
        return body.getProperties() == null ? null : body.getProperties().get(AiExceptionHandler.SETUP_HINT_PROPERTY);
    }

    @Test
    void chat404PointsToGcpLocation() {
        var h = hint(new AiExceptionHandler(vertex("asia-south1", null)), new ClientException(404, "Not Found", "x"));
        assertThat((String) h).contains("chat model").contains("asia-south1").contains("GCP_LOCATION=global");
    }

    @Test
    void chat404OnGlobalSuggestsUsCentral1() {
        var h = hint(new AiExceptionHandler(vertex("global", null)), new ClientException(404, "Not Found", "x"));
        assertThat((String) h).contains("GCP_LOCATION=us-central1");
    }

    @Test
    void embedding404PointsToEmbeddingLocation() {
        var h = hint(new AiExceptionHandler(vertex("asia-south1", "us-central1")),
                new GeminiEmbeddingException("HTTP 404 (NOT_FOUND)", 404, "NOT_FOUND"));
        assertThat((String) h).contains("embedding model").contains("location us-central1")
                .contains("AI_VERTEX_EMBEDDING_LOCATION=global").doesNotContain("GCP_LOCATION=");
    }

    @Test
    void chat401PointsToCredentials() {
        var h = hint(new AiExceptionHandler(vertex("asia-south1", null)), new ClientException(401, "Unauthorized", "x"));
        assertThat((String) h).contains("application-default login");
    }

    @Test
    void noHintForQuotaOtherStatusesAiStudioOrNoSettings() {
        var vertex = new AiExceptionHandler(vertex("asia-south1", null));
        assertThat(hint(vertex, new ClientException(429, "Too Many Requests", "x"))).isNull();
        assertThat(hint(vertex, new ClientException(400, "Bad Request", "x"))).isNull();
        assertThat(hint(new AiExceptionHandler(AiProperties.defaults()), new ClientException(404, "Not Found", "x")))
                .isNull();
        assertThat(hint(new AiExceptionHandler(), new ClientException(404, "Not Found", "x"))).isNull();
    }
}
