package com.keevo.shared.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP of a request for the per-IP rate limits: the right-most {@code X-Forwarded-For} entry (the one the
 * reverse proxy appended — a client cannot forge it), else the socket address.
 */
public final class ClientIps {

    private ClientIps() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        return request.getRemoteAddr();
    }
}
