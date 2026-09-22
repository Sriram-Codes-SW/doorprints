package com.househunt.ai.vertex;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.IOException;
import java.util.List;

/**
 * {@link AccessTokenSource} backed by Google Application Default Credentials (ADC), which the google-auth library
 * resolves in this order: {@code GOOGLE_APPLICATION_CREDENTIALS} (in GitHub Actions: the Workload Identity Federation
 * credential file written by {@code google-github-actions/auth}), the gcloud user ADC file
 * ({@code gcloud auth application-default login}), then the metadata server (the service account attached to Cloud
 * Run / GCE). No key or secret is ever configured in the app.
 *
 * <p>Deliberately no {@code toString()} exposing the credentials, and error messages carry the exception class and
 * the library's explanation only (never a token).
 */
public final class GoogleAccessTokenSource implements AccessTokenSource {

    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final GoogleCredentials credentials;

    public GoogleAccessTokenSource(GoogleCredentials credentials) {
        if (credentials == null) throw new IllegalArgumentException("credentials must not be null");
        this.credentials = credentials;
    }

    /**
     * Loads ADC once at startup (only ever called with {@code app.ai.enabled=true} and {@code app.ai.provider=vertex}).
     *
     * @throws IllegalStateException with setup instructions when no credentials can be found
     */
    public static GoogleAccessTokenSource applicationDefault() {
        try {
            return new GoogleAccessTokenSource(
                    GoogleCredentials.getApplicationDefault().createScoped(List.of(CLOUD_PLATFORM_SCOPE)));
        } catch (IOException e) {
            throw new IllegalStateException("AI_PROVIDER=vertex needs Google Application Default Credentials: run "
                    + "'gcloud auth application-default login' locally, attach a service account with "
                    + "roles/aiplatform.user on Cloud Run, or use google-github-actions/auth in CI "
                    + "(docs/ai/vertex-setup.md). Cause: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** For the google-genai {@code Client}, which refreshes the same credentials itself. */
    public GoogleCredentials credentials() {
        return credentials;
    }

    @Override
    public String accessToken() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            throw new VertexAuthException("Could not obtain a Google access token from Application Default "
                    + "Credentials (" + e.getClass().getSimpleName() + ")");
        }
        AccessToken token = credentials.getAccessToken();
        if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
            throw new VertexAuthException("Application Default Credentials returned no access token");
        }
        return token.getTokenValue();
    }

    @Override
    public String quotaProjectId() {
        return credentials.getQuotaProjectId();
    }

    @Override
    public String toString() {
        return "GoogleAccessTokenSource[" + credentials.getClass().getSimpleName() + "]";
    }

    /** No token could be obtained; the message never contains a token or credential file content. */
    public static class VertexAuthException extends RuntimeException {
        public VertexAuthException(String message) {
            super(message);
        }
    }
}
