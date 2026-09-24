package app.doorprints.server.ai.vertex;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import app.doorprints.server.ai.config.AiConfiguration;
import app.doorprints.server.ai.config.AiProperties;
import app.doorprints.server.ai.embedding.VertexEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Vertex AI provider ({@code app.ai.enabled=true} and {@code app.ai.provider=vertex}; nothing here is loaded
 * otherwise, so AI-disabled and AI Studio startups never touch Google credentials).
 *
 * <ul>
 *   <li>Chat: Spring AI 2.0.1's {@code GoogleGenAiChatAutoConfiguration} builds {@code GoogleGenAiChatModel} from the
 *   {@link Client} bean below (its own {@code googleGenAiClient} bean is {@code @ConditionalOnMissingBean}). Our client
 *   is in Vertex mode with explicit project/location, Application Default Credentials, the app's timeout and a bounded
 *   retry policy (the SDK default is 5 attempts), and never reads an API key (the SDK ignores
 *   {@code GOOGLE_API_KEY} when credentials are passed).</li>
 *   <li>Embeddings: {@link VertexEmbeddingModel} (same model, dimensions, task type, retries and timeout settings as
 *   the AI Studio path, from {@code app.ai.embedding.*}); the only {@code EmbeddingModel}, picked up by PgVector.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.ai.enabled")
@ConditionalOnProperty(name = "app.ai.provider", havingValue = AiProperties.VERTEX)
public class VertexAiConfiguration {

    /** Status codes the google-genai SDK retries (its default list); 4xx other than 408/429 are never retried. */
    static final Integer[] RETRY_STATUS_CODES = {408, 429, 500, 502, 503, 504};
    static final double RETRY_INITIAL_DELAY_SECONDS = 1.0;

    /** ADC, loaded once. Validates the Vertex settings first so a missing project id fails with a clear message. */
    @Bean
    public GoogleAccessTokenSource vertexAccessTokens(AiProperties props) {
        AiConfiguration.validateVertex(props.vertex());
        return GoogleAccessTokenSource.applicationDefault();
    }

    @Bean
    public Client googleGenAiClient(AiProperties props, GoogleAccessTokenSource vertexAccessTokens) {
        return client(props, vertexAccessTokens);
    }

    static Client client(AiProperties props, GoogleAccessTokenSource tokens) {
        return client(props, tokens, RETRY_INITIAL_DELAY_SECONDS);
    }

    /**
     * Package-visible for tests (built against a local endpoint with fixed credentials and a tiny delay).
     *
     * <p>Retry policy: {@code AI_MAX_RETRIES + 1} attempts (default 3) on 408/429/5xx with exponential backoff
     * (1 s, 2 s, ... +/-50% jitter, at most 10 s). The SDK's RetryInterceptor (1.65.0) does not read Retry-After and
     * also sleeps once after the last failed attempt, so the delays are kept short; a quota error still surfaces within
     * seconds as {@code ClientException} code 429, which {@code ProviderErrors} classifies.
     */
    static Client client(AiProperties props, GoogleAccessTokenSource tokens, double initialDelaySeconds) {
        var v = props.vertex();
        var e = props.embedding();
        var retry = HttpRetryOptions.builder()
                .attempts(e.maxRetries() + 1)
                .initialDelay(initialDelaySeconds)
                .maxDelay(10.0)
                .jitter(0.5)
                .httpStatusCodes(RETRY_STATUS_CODES)
                .build();
        var http = HttpOptions.builder()
                .apiVersion(v.apiVersion())
                .timeout(Math.toIntExact(e.timeout().toMillis()))
                .retryOptions(retry);
        if (!v.endpoint().isEmpty()) http.baseUrl(v.endpoint());
        return Client.builder()
                .vertexAI(true)
                .project(v.projectId())
                .location(v.location())
                .credentials(tokens.credentials())
                .httpOptions(http.build())
                .build();
    }

    @Bean
    public EmbeddingModel vertexEmbeddingModel(AiProperties props, GoogleAccessTokenSource vertexAccessTokens) {
        var v = props.vertex();
        var e = props.embedding();
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(e.timeout());
        var builder = RestClient.builder().requestFactory(requestFactory);
        return new VertexEmbeddingModel(builder,
                VertexEndpoints.versioned(v.endpoint(), v.embeddingLocation(), v.apiVersion()),
                v.projectId(), v.embeddingLocation(), e.model(), e.dimensions(), e.taskType(), e.maxRetries(),
                Duration.ofSeconds(2), vertexAccessTokens);
    }
}
