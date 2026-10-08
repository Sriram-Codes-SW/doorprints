/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.config;

import app.doorprints.server.common.Problems;
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

    /**
     * Refuses a body whose declared length is over the cap with 413. A body without a declared length is wrapped so
     * reading stops once the cap is passed.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request);
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            Problems.write(response, 413, "Request body too large (max " + limit + " bytes)");
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

    /**
     * Counts the bytes handed out and throws IOException once more than the cap has been read.
     */
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
