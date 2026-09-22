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
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var type = request.getContentType();
        return type != null && type.regionMatches(true, 0, "multipart/", 0, 10);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":413,\"detail\":\"Request body too large (max " + maxBytes + " bytes)\"}");
            return;
        }
        chain.doFilter(declared >= 0 ? request : new Limited(request, maxBytes), response);
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
