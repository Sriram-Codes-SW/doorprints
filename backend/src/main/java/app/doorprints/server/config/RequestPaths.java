package app.doorprints.server.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Path checks shared by the security filters (threat model F-20).
 *
 * <p>Servlet containers and Spring MVC decode and normalise the request path before matching a controller
 * ({@code /api;x/houses}, {@code /%61pi/houses}, {@code //api/houses} and {@code /api/./houses} can all reach
 * {@code /api/houses}), while {@link HttpServletRequest#getRequestURI()} is the raw, undecoded path. Instead of trying
 * to reproduce that normalisation, the API refuses every path that <em>needs</em> it: no legitimate Doorprints client
 * ever sends path parameters, percent-encoding, backslashes, empty segments or dot segments in the path (ids are
 * UUIDs, and free text such as a street name travels in the query string). After that check the raw path equals the
 * path Spring matches, so simple string comparisons on it are safe.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** The raw request path without the context path (query string excluded). */
    public static String path(HttpServletRequest request) {
        var uri = request.getRequestURI();
        if (uri == null) return "";
        var context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) uri = uri.substring(context.length());
        return uri;
    }

    /**
     * True when {@code path} is already in canonical form: starts with {@code /}, printable ASCII only, and has no
     * {@code ;}, {@code %}, {@code \}, empty segment ({@code //}), {@code .} or {@code ..} segment, or a segment that
     * ends with a dot (Windows-style {@code /api/houses.}).
     */
    public static boolean isCanonical(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x21 || c > 0x7e || c == ';' || c == '%' || c == '\\') return false;
        }
        if (path.length() == 1) return true;
        // A single trailing slash ("/api/houses/") is tolerated by nobody here, so treat it as an empty segment.
        var segments = path.substring(1).split("/", -1);
        for (var segment : segments) {
            if (segment.isEmpty() || segment.endsWith(".")) return false;
        }
        return true;
    }

    /** Case-sensitive prefix match on a canonical path: {@code /api} matches {@code /api} and {@code /api/x}. */
    public static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** A CORS preflight: OPTIONS with an Origin and an Access-Control-Request-Method header. */
    public static boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod())
                && request.getHeader("Origin") != null
                && request.getHeader("Access-Control-Request-Method") != null;
    }

    /** Shortened path with control and non-ASCII characters replaced, safe for one log line. */
    static String forLog(String path) {
        var p = path == null ? "" : path;
        p = p.length() > 120 ? p.substring(0, 120) + "..." : p;
        return p.replaceAll("[^\\x20-\\x7e]", "?");
    }
}
