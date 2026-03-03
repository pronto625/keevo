package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * ApiResponseWrapper — Standardised API response envelope.
 *
 * <p>Every controller endpoint returns this wrapper.
 *
 * <p>Success:
 * <pre>{@code { "data": {...}, "timestamp": "..." } }</pre>
 *
 * <p>Error:
 * <pre>{@code { "error": "message", "code": "NOT_FOUND", "domainCode": "...", "details": {...}, "timestamp": "..." } }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponseWrapper<T>(
        T data,
        String error,
        String code,
        String domainCode,
        Object details,
        Instant timestamp
) {
    public static <T> ApiResponseWrapper<T> ok(T data) {
        return new ApiResponseWrapper<>(data, null, null, null, null, Instant.now());
    }

    public static <T> ApiResponseWrapper<T> error(
            String error, String code, String domainCode, Object details) {
        return new ApiResponseWrapper<>(null, error, code, domainCode, details, Instant.now());
    }
}
