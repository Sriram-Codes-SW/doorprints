package com.househunt.ai.vertex;

import com.househunt.ai.config.AiConfiguration;
import com.househunt.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VertexSettingsTest {

    @Test
    void endpointsFollowTheSdkRules() {
        assertThat(VertexEndpoints.root("", "asia-south1")).isEqualTo("https://asia-south1-aiplatform.googleapis.com");
        assertThat(VertexEndpoints.root(null, "global")).isEqualTo("https://aiplatform.googleapis.com");
        assertThat(VertexEndpoints.root("", "eu")).isEqualTo("https://aiplatform.eu.rep.googleapis.com");
        assertThat(VertexEndpoints.root("http://127.0.0.1:9999/", "asia-south1")).isEqualTo("http://127.0.0.1:9999");
        assertThat(VertexEndpoints.versioned("", "us-central1", "v1"))
                .isEqualTo("https://us-central1-aiplatform.googleapis.com/v1");
    }

    @Test
    void defaultsAreMumbaiAndV1beta1AndEmbeddingLocationFollowsChat() {
        var v = new AiProperties.Vertex(" doorprints-ai ", null, "", null, null);
        assertThat(v.projectId()).isEqualTo("doorprints-ai");
        assertThat(v.location()).isEqualTo("asia-south1");
        assertThat(v.embeddingLocation()).isEqualTo("asia-south1");
        assertThat(v.apiVersion()).isEqualTo("v1beta1");
        assertThat(new AiProperties.Vertex("p", "Global", "US-Central1", null, null).embeddingLocation())
                .isEqualTo("us-central1");
    }

    @Test
    void providerDefaultsToAiStudio() {
        assertThat(AiProperties.defaults().provider()).isEqualTo(AiProperties.AISTUDIO);
        assertThat(AiProperties.defaults().vertexProvider()).isFalse();
        assertThat(AiProperties.defaults().indexOnChange()).isTrue();
        assertThat(AiProperties.normalizeProvider(" VERTEX ")).isEqualTo(AiProperties.VERTEX);
    }

    @Test
    void vertexValidationNeedsAProjectAndSaneLocations() {
        assertThatCode(() -> AiConfiguration.validateVertex(new AiProperties.Vertex("doorprints-ai", null, null, null, null)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AiConfiguration.validateVertex(new AiProperties.Vertex("", null, null, null, null)))
                .hasMessageContaining("GCP_PROJECT_ID");
        assertThatThrownBy(() -> AiConfiguration.validateVertex(new AiProperties.Vertex("doorprints-ai", "asia south1", null, null, null)))
                .hasMessageContaining("location");
        assertThatThrownBy(() -> AiConfiguration.validateVertex(new AiProperties.Vertex("doorprints-ai", null, null, "ftp://x", null)))
                .hasMessageContaining("AI_VERTEX_ENDPOINT");
    }
}
