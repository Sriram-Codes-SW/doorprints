package com.househunt.ai.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.househunt.ai.embedding.GeminiEmbeddingModel.Content;
import com.househunt.ai.embedding.GeminiEmbeddingModel.ContentEmbedding;
import com.househunt.ai.embedding.GeminiEmbeddingModel.GeminiEmbeddingException;
import com.househunt.ai.embedding.GeminiEmbeddingModel.Part;
import com.househunt.ai.vertex.AccessTokenSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@link EmbeddingModel} for Gemini embedding models on Google Cloud Vertex AI (Gemini Enterprise Agent Platform),
 * authenticated with an OAuth access token from Application Default Credentials.
 *
 * <p>Wire format, verified against the official google-genai Java SDK 1.65.0 ({@code Models.embedContent} in Vertex
 * mode, which Spring AI 2.0.1 uses): Gemini embedding models except {@code gemini-embedding-001} use
 * {@code POST .../publishers/google/models/{model}:embedContent} with ONE content per request
 * ({@code {"content":{"parts":[{"text":...}]},"embedContentConfig":{"outputDimensionality":768}}} ->
 * {@code {"embedding":{"values":[...]},"usageMetadata":{...}}}); the SDK itself throws for more than one content.
 * {@code gemini-embedding-001} and the older text models use {@code :predict}
 * ({@code {"instances":[{"content":"...","task_type":...}],"parameters":{"outputDimensionality":768}}} ->
 * {@code {"predictions":[{"embeddings":{"values":[...],"statistics":{...}}}]}}), also one text per request because
 * gemini-embedding-001 accepts a single input per call. So a batch of N texts costs N HTTP calls, sent sequentially.
 *
 * <p>Why not Spring AI's {@code GoogleGenAiTextEmbeddingModel}: it passes the whole batch to the SDK, which rejects
 * more than one text for gemini-embedding-2 on Vertex, and its connection auto-configuration has no enable switch
 * (docs/ai/ai-design.md 3.1). Retries, Retry-After / RetryInfo handling, L2 normalisation, error messages (HTTP status
 * and google.rpc reason only, never the body or the text) and the exception type are the same as
 * {@link GeminiEmbeddingModel}, so callers (indexer, eval harness, quota detection) behave identically for both
 * providers.
 */
public class VertexEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(VertexEmbeddingModel.class);

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,99}");
    private static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final Pattern LOCATION = Pattern.compile("[a-z][a-z0-9-]{1,40}");

    private final RestClient client;
    private final String project;
    private final String location;
    private final String model;
    private final boolean embedContentApi;
    private final int dimensions;
    private final String taskType;
    private final int maxRetries;
    private final Duration backoff;
    private final AccessTokenSource tokens;
    private final Consumer<Duration> sleeper;

    /**
     * @param builder    a builder with the HTTP request factory (timeouts) already set; tests bind a
     *                   {@code MockRestServiceServer} to it
     * @param baseUrl    versioned base URL, e.g. {@code https://asia-south1-aiplatform.googleapis.com/v1beta1}
     * @param project    Google Cloud project id
     * @param location   Vertex AI location of the embedding model, e.g. {@code asia-south1} or {@code global}
     * @param model      e.g. {@code gemini-embedding-2} (a leading {@code models/} or {@code publishers/google/models/}
     *                   is accepted)
     * @param dimensions output dimensionality; must match the pgvector column (768)
     * @param taskType   optional ({@code null}), e.g. {@code RETRIEVAL_DOCUMENT}
     * @param maxRetries extra attempts on 429, 5xx and I/O errors
     * @param backoff    base wait between attempts (multiplied by the attempt number)
     * @param tokens     OAuth access tokens (Application Default Credentials in production)
     */
    public VertexEmbeddingModel(RestClient.Builder builder, String baseUrl, String project, String location,
                                String model, int dimensions, String taskType, int maxRetries, Duration backoff,
                                AccessTokenSource tokens) {
        this(builder, baseUrl, project, location, model, dimensions, taskType, maxRetries, backoff, tokens,
                VertexEmbeddingModel::sleep);
    }

    /** As above, with the wait between attempts injectable (tests record the waits instead of sleeping). */
    VertexEmbeddingModel(RestClient.Builder builder, String baseUrl, String project, String location, String model,
                         int dimensions, String taskType, int maxRetries, Duration backoff, AccessTokenSource tokens,
                         Consumer<Duration> sleeper) {
        if (tokens == null) throw new IllegalArgumentException("Vertex AI access token source is missing");
        if (project == null || !PROJECT.matcher(project).matches()) {
            throw new IllegalArgumentException("Invalid Google Cloud project id");
        }
        if (location == null || !LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("Invalid Vertex AI location");
        }
        var name = model == null ? "" : model.strip();
        if (name.startsWith("publishers/google/models/")) name = name.substring("publishers/google/models/".length());
        if (name.startsWith("models/")) name = name.substring("models/".length());
        if (!MODEL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Vertex AI embedding model name");
        }
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        var base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw new IllegalArgumentException("Vertex AI base URL must be http(s)");
        }
        this.client = builder.baseUrl(base).build();
        this.project = project;
        this.location = location;
        this.model = name;
        this.embedContentApi = usesEmbedContentApi(name);
        this.dimensions = dimensions;
        this.taskType = taskType == null || taskType.isBlank() ? null : taskType.strip();
        this.maxRetries = Math.max(0, maxRetries);
        this.backoff = backoff == null || backoff.isNegative() ? Duration.ZERO : backoff;
        this.tokens = tokens;
        this.sleeper = sleeper;
    }

    /**
     * Same rule as the google-genai SDK's {@code Transformers.tIsVertexEmbedContentModel}: Gemini embedding models
     * except gemini-embedding-001 (and model-as-a-service models) use {@code :embedContent}, the rest {@code :predict}.
     */
    static boolean usesEmbedContentApi(String model) {
        return (model.contains("gemini") && !model.contains("gemini-embedding-001")) || model.contains("maas");
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("No text to embed");
        }
        for (var text : texts) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Cannot embed blank text");
        }
        var results = new ArrayList<Embedding>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(new Embedding(embedOne(texts.get(i)), i));
        }
        var metadata = new EmbeddingResponseMetadata();
        metadata.setModel(model);
        return new EmbeddingResponse(results, metadata);
    }

    @Override
    public float[] embed(Document document) {
        var text = document.getText();
        return embed(text == null ? "" : text);
    }

    /** Fixed by configuration (the pgvector column size), so no probe call is ever made. */
    @Override
    public int dimensions() {
        return dimensions;
    }

    public String model() {
        return model;
    }

    boolean embedContentApi() {
        return embedContentApi;
    }

    private float[] embedOne(String text) {
        List<Double> values;
        if (embedContentApi) {
            var body = new EmbedContentRequest(new Content(List.of(new Part(text))),
                    new EmbedContentConfig(dimensions, taskType));
            var response = post("embedContent", body, EmbedContentResponse.class);
            values = response == null || response.embedding() == null ? null : response.embedding().values();
        } else {
            var body = new PredictRequest(List.of(new PredictInstance(text, taskType)),
                    new PredictParameters(dimensions));
            var response = post("predict", body, PredictResponse.class);
            var predictions = response == null ? null : response.predictions();
            if (predictions == null || predictions.size() != 1) {
                throw new GeminiEmbeddingException("Vertex AI returned " + (predictions == null ? 0 : predictions.size())
                        + " predictions for 1 text");
            }
            var p = predictions.getFirst();
            values = p == null || p.embeddings() == null ? null : p.embeddings().values();
        }
        if (values == null || values.size() != dimensions) {
            throw new GeminiEmbeddingException("Vertex AI returned an embedding of size "
                    + (values == null ? 0 : values.size()) + ", expected " + dimensions);
        }
        return GeminiEmbeddingModel.normalize(values);
    }

    private <T> T post(String method, Object body, Class<T> type) {
        for (int attempt = 0; ; attempt++) {
            Duration wait = backoff.multipliedBy(attempt + 1L);
            final String token = accessToken();
            final String quotaProject = tokens.quotaProjectId();
            try {
                return client.post()
                        .uri("/projects/{project}/locations/{location}/publishers/google/models/{model}:" + method,
                                project, location, model)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(h -> {
                            h.setBearerAuth(token);
                            if (quotaProject != null && !quotaProject.isBlank()) h.set(USER_PROJECT_HEADER, quotaProject);
                        })
                        .body(body)
                        .retrieve()
                        .body(type);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxRetries) {
                    var reason = GeminiEmbeddingModel.errorReason(e);
                    throw new GeminiEmbeddingException("Vertex AI embedding request failed: HTTP " + status
                            + (reason == null ? "" : " (" + reason + ")") + hint(status), status, reason);
                }
                var serverHint = GeminiEmbeddingModel.serverRetryHint(e, Instant.now());
                if (serverHint != null) {
                    if (serverHint.compareTo(GeminiEmbeddingModel.MAX_RETRY_WAIT) > 0) {
                        throw new GeminiEmbeddingException("Vertex AI embedding request failed: HTTP " + status
                                + " (server asks to retry after " + serverHint.toSeconds() + " s)", status,
                                GeminiEmbeddingModel.errorReason(e));
                    }
                    if (serverHint.compareTo(wait) > 0) wait = serverHint;
                }
                log.debug("Vertex AI embedding HTTP {} (attempt {}), retrying in {} ms", status, attempt + 1,
                        wait.toMillis());
            } catch (ResourceAccessException e) {
                if (attempt >= maxRetries) {
                    throw new GeminiEmbeddingException("Vertex AI embedding request failed: I/O error ("
                            + e.getClass().getSimpleName() + ")");
                }
                log.debug("Vertex AI embedding I/O error (attempt {}), retrying", attempt + 1);
            }
            sleeper.accept(wait);
        }
    }

    static final String USER_PROJECT_HEADER = "x-goog-user-project";

    /** Not retried: missing or broken credentials do not heal within a request. */
    private String accessToken() {
        try {
            var token = tokens.accessToken();
            if (token == null || token.isBlank()) throw new IllegalStateException("empty token");
            return token;
        } catch (RuntimeException e) {
            throw new GeminiEmbeddingException("Vertex AI embedding request failed: no access token ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** Setup hints for the errors a first-time owner is most likely to hit (no response content is included). */
    private String hint(int status) {
        return switch (status) {
            case 401 -> " - the access token was rejected; re-run 'gcloud auth application-default login' or check "
                    + "the Workload Identity Federation setup";
            case 403 -> " - check that the Vertex AI API is enabled in project " + project
                    + " and the service account has roles/aiplatform.user";
            case 404 -> " - model " + model + " is not available in location " + location
                    + "; set AI_VERTEX_EMBEDDING_LOCATION (e.g. us-central1 or global)";
            default -> "";
        };
    }

    private static void sleep(Duration d) {
        if (d.isZero()) return;
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiEmbeddingException("Interrupted while waiting to retry");
        }
    }

    public record EmbedContentRequest(Content content, EmbedContentConfig embedContentConfig) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmbedContentConfig(Integer outputDimensionality, String taskType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedContentResponse(ContentEmbedding embedding) {
    }

    public record PredictRequest(List<PredictInstance> instances, PredictParameters parameters) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PredictInstance(String content, @JsonProperty("task_type") String taskType) {
    }

    public record PredictParameters(Integer outputDimensionality) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PredictResponse(List<Prediction> predictions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Prediction(ContentEmbedding embeddings) {
    }
}
