package com.househunt.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Caps non-multipart request bodies (JSON) at {@code app.limits.max-json-bytes} (F-05). Multipart uploads are
 * limited separately by {@code spring.servlet.multipart.*}. A declared Content-Length over the cap is answered with
 * 413 straight away; a chunked body is counted while it is read and fails once it passes the cap (Spring then answers
 * 400 because the body could not be read).
 *
 * <p>One path may be given a larger cap: {@code POST /api/import} carries a whole backup file
 * ({@code app.limits.max-import-bytes}). The check runs on the raw request path, before the canonical-path check in
 * {@link ApiKeyFilter}, so a dressed-up path such as {@code /api/import;x} does not match the larger cap here and is
 * refused with 400 a moment later anyway.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final String largerPath;
    private final long largerMaxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        this(maxBytes, null, maxBytes);
    }

    /** @param largerPath the one path (and its sub-paths) that may send up to {@code largerMaxBytes}; may be null */
    public RequestSizeLimitFilter(long maxBytes, String largerPath, long largerMaxBytes) {
        this.maxBytes = maxBytes;
        this.largerPath = largerPath;
        this.largerMaxBytes = Math.max(maxBytes, largerMaxBytes);
    }

    private long limitFor(HttpServletRequest request) {
        if (largerPath == null) return maxBytes;
        return RequestPaths.isUnder(RequestPaths.path(request), largerPath) ? largerMaxBytes : maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var type = request.getContentType();
        return type != null && type.regionMatches(true, 0, "multipart/", 0, 10);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":413,\"detail\":\"Request body too large (max " + limit + " bytes)\"}");
            return;
        }
        chain.doFilter(declared >= 0 ? request : new Limited(request, limit), response);
    }

    /** Wraps the body stream of a request without Content-Length and stops reading after the cap. */
    private static final class Limited extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new CountingStream(super.getInputStream(), max);
            return stream;
        }
    }

    private static final class CountingStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long max;
        private long count;

        CountingStream(ServletInputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        private void add(long n) throws IOException {
            if (n > 0) count += n;
            if (count > max) throw new IOException("Request body too large (max " + max + " bytes)");
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            add(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
