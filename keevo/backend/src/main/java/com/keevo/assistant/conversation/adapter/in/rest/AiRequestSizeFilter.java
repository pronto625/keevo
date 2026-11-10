package com.keevo.assistant.conversation.adapter.in.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Story 21.4.2 — bounds the bodies of the assistant's routes ({@code POST/PUT /api/v1/ai/**}) before Jackson reads
 * them: a conversation is at most 512 KB once stored, a question 2 000 characters with 20 history messages, so
 * {@value #MAX_BODY_BYTES} bytes is ample. A declared larger body answers 413 without being read; a body without a
 * declared length (chunked) is cut at the limit, which fails the JSON parsing (400). Without it, one PUT of 500 huge
 * strings would be fully parsed in memory before the 512 KB check (review of 2026-11-10).
 */
@Component
@Order(3)
public class AiRequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1_048_576;
    static final String PREFIX = "/api/v1/ai/";
    static final String BODY = "{\"error\":\"La requête est trop volumineuse.\",\"code\":\"PAYLOAD_TOO_LARGE\","
            + "\"domainCode\":\"VALIDATION_ERROR\"}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return !path.startsWith(PREFIX) || !("POST".equals(method) || "PUT".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BODY);
            return;
        }
        chain.doFilter(declared >= 0 ? request : new Bounded(request), response);
    }

    /** A request whose body stops at {@link #MAX_BODY_BYTES} (an {@link IOException} beyond). */
    static final class Bounded extends HttpServletRequestWrapper {

        Bounded(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++read > MAX_BODY_BYTES) {
                        throw new IOException("request body over " + MAX_BODY_BYTES + " bytes");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = in.read(buffer, off, len);
                    if (n > 0 && (read += n) > MAX_BODY_BYTES) {
                        throw new IOException("request body over " + MAX_BODY_BYTES + " bytes");
                    }
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
            };
        }
    }
}
