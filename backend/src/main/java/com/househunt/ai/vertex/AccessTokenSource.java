package com.househunt.ai.vertex;

/**
 * Supplies the OAuth 2.0 access token sent as {@code Authorization: Bearer ...} to Vertex AI. Production uses
 * {@link GoogleAccessTokenSource} (Application Default Credentials); tests pass a fixed token.
 */
@FunctionalInterface
public interface AccessTokenSource {

    /** A currently valid access token (refreshed when needed). Never logged, never put in exception messages. */
    String accessToken();

    /** Project billed for quota ({@code x-goog-user-project}), or {@code null} to bill the resource's project. */
    default String quotaProjectId() {
        return null;
    }
}
