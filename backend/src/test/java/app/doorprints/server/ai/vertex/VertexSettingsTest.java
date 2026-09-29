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

package app.doorprints.server.ai.vertex;

import app.doorprints.server.ai.config.AiConfiguration;
import app.doorprints.server.ai.config.AiProperties;
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
