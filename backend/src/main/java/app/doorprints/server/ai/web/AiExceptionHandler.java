package app.doorprints.server.ai.web;

import app.doorprints.server.ai.ProviderErrors;
import app.doorprints.server.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);

    /** Problem-detail {@code code} when the provider (AI Studio or Vertex AI) answered 429 / RESOURCE_EXHAUSTED. */
    public static final String QUOTA_EXHAUSTED_CODE = "AI_QUOTA_EXHAUSTED";
    /** Suggested wait for clients after a provider quota error (per-minute quotas reset within a minute). */
    static final String QUOTA_RETRY_AFTER_SECONDS = "60";
    /** Problem-detail property with an owner setup hint (Vertex AI 401/403/404: auth, IAM, model not in location). */
    public static final String SETUP_HINT_PROPERTY = "setupHint";

    /** Null in unit tests that build the handler directly: then no Vertex setup hints are added. */
    private final AiProperties props;

    /** For tests: no provider settings, so no setup hints. */
    public AiExceptionHandler() {
        this((AiProperties) null);
    }

    public AiExceptionHandler(AiProperties props) {
        this.props = props;
    }

    /** Spring: {@code AiProperties} is bound by {@code @ConfigurationPropertiesScan}; tolerate its absence (slices). */
    @Autowired
    public AiExceptionHandler(ObjectProvider<AiProperties> props) {
        this(props.getIfAvailable());
    }

    /**
     * Always 503 (the apps already show "AI provider unavailable" for it); a quota error additionally carries
     * {@code code: AI_QUOTA_EXHAUSTED} and {@code Retry-After: 60} so callers (the eval harness, future UI) can tell it
     * apart from an outage without parsing text.
     */
    @ExceptionHandler(AiUnavailableException.class)
    public ResponseEntity<ProblemDetail> aiUnavailable(AiUnavailableException e) {
        // Log the cause class and message only: provider errors can echo parts of the prompt.
        var cause = e.getCause();
        boolean quota = ProviderErrors.isQuotaExhausted(e);
        String hint = quota ? null : vertexSetupHint(e);
        log.warn("{}{} ({}: {}){}", e.getMessage(), quota ? " [provider quota exhausted]" : "",
                cause == null ? "-" : cause.getClass().getSimpleName(),
                cause == null ? "-" : abbreviate(cause.getMessage()),
                hint == null ? "" : " [setup: " + hint + "]");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, quota
                ? e.getMessage() + ". The AI provider's quota or rate limit is exhausted; try again later."
                : e.getMessage() + ". The AI provider is unavailable or its free quota is exhausted; try again later.");
        problem.setProperty("retryable", true);
        if (hint != null) problem.setProperty(SETUP_HINT_PROPERTY, hint);
        var response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (quota) {
            problem.setProperty("code", QUOTA_EXHAUSTED_CODE);
            response.header(HttpHeaders.RETRY_AFTER, QUOTA_RETRY_AFTER_SECONDS);
        }
        return response.body(problem);
    }

    /**
     * Owner-facing hint for the Vertex AI errors a first setup hits: 404 (model not offered in the location, likely
     * with the unconfirmed {@code asia-south1} default), 403 (API disabled / missing role, or model not offered to the
     * project there) and 401 (credentials). Chat points to {@code GCP_LOCATION}, embeddings to
     * {@code AI_VERTEX_EMBEDDING_LOCATION}. Only env-var names and the configured location are included, never the
     * project id or any provider message. {@code null} when not on Vertex or not one of these statuses.
     */
    String vertexSetupHint(Throwable error) {
        if (props == null || !props.vertexProvider()) return null;
        var failure = ProviderErrors.httpFailure(error).orElse(null);
        if (failure == null) return null;
        boolean chat = failure.call() == ProviderErrors.Call.CHAT;
        String what = chat ? "chat model (AI_CHAT_MODEL)" : "embedding model (AI_EMBEDDING_MODEL)";
        String location = chat ? props.vertex().location() : props.vertex().embeddingLocation();
        String locationVar = chat ? "GCP_LOCATION" : "AI_VERTEX_EMBEDDING_LOCATION";
        String other = "global".equals(location) ? "us-central1" : "global";
        return switch (failure.status()) {
            case 404 -> "Vertex AI answered 404: the " + what + " is not available in location " + location
                    + "; set " + locationVar + "=" + other + " (or another location that offers it) and restart. "
                    + "See docs/ai/vertex-setup.md step 8.";
            case 403 -> "Vertex AI answered 403: check that the Vertex AI API is enabled in GCP_PROJECT_ID and the "
                    + "service account has roles/aiplatform.user; if both are fine, the " + what
                    + " may not be offered in location " + location + ": try " + locationVar + "=" + other
                    + ". See docs/ai/vertex-setup.md steps 2, 3 and 8.";
            case 401 -> "Vertex AI answered 401: the Application Default Credentials were rejected; run "
                    + "'gcloud auth application-default login' locally or check the Workload Identity Federation "
                    + "setup (docs/ai/vertex-setup.md).";
            default -> null;
        };
    }

    private static String abbreviate(String s) {
        if (s == null) return "-";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
