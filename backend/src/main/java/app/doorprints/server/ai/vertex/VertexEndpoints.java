package app.doorprints.server.ai.vertex;

import java.util.Locale;

/**
 * Vertex AI REST base URLs, derived from the location exactly like the google-genai Java SDK 1.65.0 does
 * ({@code ApiClient}): {@code global} -> {@code https://aiplatform.googleapis.com}, the multi-regions {@code us} /
 * {@code eu} -> {@code https://aiplatform.<loc>.rep.googleapis.com}, any region ->
 * {@code https://<region>-aiplatform.googleapis.com}. An explicit endpoint (tests, Private Service Connect) wins.
 */
public final class VertexEndpoints {

    private VertexEndpoints() {
    }

    /** Base URL without the API version and without a trailing slash. */
    public static String root(String endpointOverride, String location) {
        if (endpointOverride != null && !endpointOverride.isBlank()) return stripSlash(endpointOverride.strip());
        var loc = location == null ? "" : location.strip().toLowerCase(Locale.ROOT);
        if (loc.isEmpty() || loc.equals("global")) return "https://aiplatform.googleapis.com";
        if (loc.equals("us") || loc.equals("eu")) return "https://aiplatform." + loc + ".rep.googleapis.com";
        return "https://" + loc + "-aiplatform.googleapis.com";
    }

    /** {@link #root} plus {@code /<apiVersion>}, e.g. {@code https://asia-south1-aiplatform.googleapis.com/v1beta1}. */
    public static String versioned(String endpointOverride, String location, String apiVersion) {
        return root(endpointOverride, location) + "/" + apiVersion;
    }

    private static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
