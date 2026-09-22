package com.househunt.ai.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@link EmbeddingModel} for the native Gemini API: {@code POST {baseUrl}/models/{model}:batchEmbedContents}.
 *
 * <p>Why not the OpenAI-compatible endpoint: Gemini's {@code /v1beta/openai/embeddings} returns {@code data[]} items
 * without the {@code index} field, and the openai-java SDK behind Spring AI 2.0.1's OpenAI starter rejects that
 * ({@code OpenAIInvalidDataException: index is not set}), so every embedding call failed. Why not Spring AI's own
 * {@code spring-ai-starter-model-google-genai-embedding}: see docs/ai/ai-design.md section 3.1 (its connection
 * auto-configuration has no enable switch and fails startup without a key, it ignores the task type, and it adds the
 * google-genai SDK + Google auth libraries to the scanned SBOM for one HTTP call).
 *
 * <p>Request shape follows what the official google-genai Java SDK sends in Gemini Developer API mode: one
 * {@code requests[]} entry per text with {@code model}, {@code content.parts[].text}, {@code outputDimensionality}
 * and optionally {@code taskType}. The response is {@code embeddings[].values}, in request order.
 *
 * <p>Security: the API key goes only in the {@code x-goog-api-key} header (never in the URL, so it cannot leak into
 * access logs or exception messages), and error messages carry the HTTP status only, never the response body (which
 * can echo the embedded text).
 */
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingModel.class);

    /** Gemini API limit for requests in one batchEmbedContents call. */
    static final int MAX_BATCH = 100;
    static final String API_KEY_HEADER = "x-goog-api-key";
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    /**
     * Longest server-requested wait (Retry-After header or google.rpc.RetryInfo) that is honoured. A free-tier
     * per-minute quota asks for at most ~60 s; a longer hint (daily quota) means retrying now is pointless, so the call
     * fails at once instead of blocking the reindex request.
     */
    static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(60);
    /**
     * The machine-readable cause in a google.rpc error body: {@code "reason": "API_KEY_INVALID"} (ErrorInfo detail),
     * else {@code "status": "INVALID_ARGUMENT"}. Only upper-case enum tokens match, so no free text (which could echo
     * the embedded text) ever gets into a message.
     */
    private static final Pattern ERROR_REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Z][A-Z0-9_]{0,63})\"");
    private static final Pattern ERROR_STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z][A-Z0-9_]{0,63})\"");
    /** {@code "retryDelay": "37s"} / {@code "12.5s"} inside the google.rpc.RetryInfo detail of a 429 body. */
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d{1,6})(?:\\.(\\d{1,9}))?s\"");

    private final RestClient client;
    private final String model;
    private final int dimensions;
    private final String taskType;
    private final int maxRetries;
    private final Duration backoff;
    private final Consumer<Duration> sleeper;

    /**
     * @param builder    a builder with the HTTP request factory (timeouts) already set; the base URL and key header are
     *                   added here. Tests bind a {@code MockRestServiceServer} to it.
     * @param baseUrl    e.g. {@code https://generativelanguage.googleapis.com/v1beta}
     * @param model      e.g. {@code gemini-embedding-2} (a leading {@code models/} is accepted)
     * @param dimensions output dimensionality; must match the pgvector column (768)
     * @param taskType   optional ({@code null}); only {@code gemini-embedding-001} accepts a task type
     * @param maxRetries extra attempts on 429, 5xx and I/O errors
     * @param backoff    base wait between attempts (multiplied by the attempt number)
     */
    public GeminiEmbeddingModel(RestClient.Builder builder, String baseUrl, String apiKey, String model,
                                int dimensions, String taskType, int maxRetries, Duration backoff) {
        this(builder, baseUrl, apiKey, model, dimensions, taskType, maxRetries, backoff, GeminiEmbeddingModel::sleep);
    }

    /** As above, with the wait between attempts injectable (tests record the waits instead of sleeping). */
    GeminiEmbeddingModel(RestClient.Builder builder, String baseUrl, String apiKey, String model, int dimensions,
                         String taskType, int maxRetries, Duration backoff, Consumer<Duration> sleeper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Gemini embedding API key is empty");
        }
        var name = model == null ? "" : model.strip();
        if (name.startsWith("models/")) name = name.substring("models/".length());
        if (!MODEL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Gemini embedding model name");
        }
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        var base = baseUrl == null ? "" : baseUrl.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            throw new IllegalArgumentException("Gemini embedding base URL must be http(s)");
        }
        this.client = builder.baseUrl(base).defaultHeader(API_KEY_HEADER, apiKey.strip()).build();
        this.model = name;
        this.dimensions = dimensions;
        this.taskType = taskType == null || taskType.isBlank() ? null : taskType.strip();
        this.maxRetries = Math.max(0, maxRetries);
        this.backoff = backoff == null || backoff.isNegative() ? Duration.ZERO : backoff;
        this.sleeper = sleeper;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("No text to embed");
        }
        var results = new ArrayList<Embedding>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            var chunk = texts.subList(from, Math.min(texts.size(), from + MAX_BATCH));
            var vectors = embedChunk(chunk);
            for (int i = 0; i < vectors.size(); i++) {
                results.add(new Embedding(vectors.get(i), from + i));
            }
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

    private List<float[]> embedChunk(List<String> texts) {
        var requests = new ArrayList<EmbedRequest>(texts.size());
        for (var text : texts) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Cannot embed blank text");
            requests.add(new EmbedRequest("models/" + model, new Content(List.of(new Part(text))), dimensions,
                    taskType));
        }
        var body = new BatchRequest(requests);
        var response = post(body);
        var embeddings = response == null ? null : response.embeddings();
        if (embeddings == null || embeddings.size() != texts.size()) {
            throw new GeminiEmbeddingException("Gemini returned " + (embeddings == null ? 0 : embeddings.size())
                    + " embeddings for " + texts.size() + " texts");
        }
        var out = new ArrayList<float[]>(embeddings.size());
        for (var e : embeddings) {
            var values = e == null ? null : e.values();
            if (values == null || values.size() != dimensions) {
                throw new GeminiEmbeddingException("Gemini returned an embedding of size "
                        + (values == null ? 0 : values.size()) + ", expected " + dimensions);
            }
            out.add(normalize(values));
        }
        return out;
    }

    private BatchResponse post(BatchRequest body) {
        for (int attempt = 0; ; attempt++) {
            Duration wait = backoff.multipliedBy(attempt + 1L);
            try {
                return client.post()
                        .uri("/models/{model}:batchEmbedContents", model)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(BatchResponse.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxRetries) {
                    var reason = errorReason(e);
                    throw new GeminiEmbeddingException("Gemini embedding request failed: HTTP " + status
                            + (reason == null ? "" : " (" + reason + ")"));
                }
                var hint = serverRetryHint(e, Instant.now());
                if (hint != null) {
                    if (hint.compareTo(MAX_RETRY_WAIT) > 0) {
                        throw new GeminiEmbeddingException("Gemini embedding request failed: HTTP " + status
                                + " (server asks to retry after " + hint.toSeconds() + " s)");
                    }
                    if (hint.compareTo(wait) > 0) wait = hint;
                }
                log.debug("Gemini embedding HTTP {} (attempt {}), retrying in {} ms", status, attempt + 1,
                        wait.toMillis());
            } catch (ResourceAccessException e) {
                if (attempt >= maxRetries) {
                    throw new GeminiEmbeddingException("Gemini embedding request failed: I/O error ("
                            + e.getClass().getSimpleName() + ")");
                }
                log.debug("Gemini embedding I/O error (attempt {}), retrying", attempt + 1);
            }
            sleeper.accept(wait);
        }
    }

    /**
     * The wait the server asked for, or {@code null}: the {@code Retry-After} header (delta-seconds or HTTP-date), else
     * the {@code retryDelay} of the google.rpc.RetryInfo detail Gemini puts in a 429 body. Only this number is read
     * from the body; the body itself is never logged or put in an exception message.
     */
    static Duration serverRetryHint(RestClientResponseException e, Instant now) {
        HttpHeaders headers = e.getResponseHeaders();
        var header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null && !header.isBlank()) {
            var value = header.strip();
            if (value.chars().allMatch(Character::isDigit) && value.length() <= 9) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            try {
                var at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT))
                        .toInstant();
                return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
            } catch (DateTimeParseException ignored) {
                // fall through to the body
            }
        }
        String body;
        try {
            body = e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (body == null || body.isEmpty()) return null;
        var m = RETRY_DELAY.matcher(body.length() > 16_384 ? body.substring(0, 16_384) : body);
        if (!m.find()) return null;
        var d = Duration.ofSeconds(Long.parseLong(m.group(1)));
        if (m.group(2) != null) {
            var frac = (m.group(2) + "000").substring(0, 3);
            d = d.plusMillis(Long.parseLong(frac));
        }
        return d;
    }

    /** {@code API_KEY_INVALID}, {@code RESOURCE_EXHAUSTED}, ... from a Google error body, or {@code null}. */
    static String errorReason(RestClientResponseException e) {
        String body;
        try {
            body = e.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (body == null || body.isEmpty()) return null;
        var head = body.length() > 16_384 ? body.substring(0, 16_384) : body;
        var m = ERROR_REASON.matcher(head);
        if (m.find()) return m.group(1);
        m = ERROR_STATUS.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /** L2-normalises the vector: gemini-embedding-2 already does for reduced sizes, gemini-embedding-001 does not. */
    static float[] normalize(List<? extends Number> values) {
        var v = new float[values.size()];
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            var n = values.get(i);
            v[i] = n == null ? 0f : n.floatValue();
            sum += (double) v[i] * v[i];
        }
        if (sum > 0) {
            float norm = (float) Math.sqrt(sum);
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
        return v;
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

    /** Provider failure; message carries no request text, response body or key. */
    public static class GeminiEmbeddingException extends RuntimeException {
        public GeminiEmbeddingException(String message) {
            super(message);
        }
    }

    public record BatchRequest(List<EmbedRequest> requests) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmbedRequest(String model, Content content, Integer outputDimensionality, String taskType) {
    }

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchResponse(List<ContentEmbedding> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentEmbedding(List<Double> values) {
    }
}
