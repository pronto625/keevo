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
 * <p>Paginated:
 * <pre>{@code { "data": [...], "meta": {"page":0,"size":20,"totalElements":150,"totalPages":8}, "timestamp": "..." } }</pre>
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
        Object meta,
        Instant timestamp
) {
    public static <T> ApiResponseWrapper<T> ok(T data) {
        return new ApiResponseWrapper<>(data, null, null, null, null, null, Instant.now());
    }

    public static <T> ApiResponseWrapper<T> error(
            String error, String code, String domainCode, Object details) {
        return new ApiResponseWrapper<>(null, error, code, domainCode, details, null, Instant.now());
    }

    /**
     * Build a paginated success response with {@code meta} containing page, size,
     * totalElements and totalPages.
     *
     * <p>The {@code meta} field is {@code @JsonInclude(NON_NULL)} so it is absent
     * from JSON serialisation when {@code null} — existing non-paginated endpoints
     * are unaffected.
     *
     * <p>v1s-15-9: inputs are validated so an inconsistent envelope can never be emitted.
     *
     * @throws IllegalArgumentException when {@code page < 0}, {@code size < 1},
     *         {@code totalElements < 0}, {@code totalPages < 0}, or when exactly one of
     *         {@code totalPages} / {@code totalElements} is 0 ({@code totalPages == 0} iff
     *         {@code totalElements == 0}); a page past the end stays valid
     */
    public static <T> ApiResponseWrapper<T> paginated(
            T data,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0: " + page);
        if (size < 1) throw new IllegalArgumentException("size must be >= 1: " + size);
        if (totalElements < 0) throw new IllegalArgumentException("totalElements must be >= 0: " + totalElements);
        if (totalPages < 0) throw new IllegalArgumentException("totalPages must be >= 0: " + totalPages);
        // A page beyond the last one is legitimate (Spring returns it with empty content): not rejected.
        if (totalElements == 0 && totalPages != 0) {
            throw new IllegalArgumentException("totalPages must be 0 when totalElements is 0: " + totalPages);
        }
        if (totalElements > 0 && totalPages < 1) {
            throw new IllegalArgumentException("totalPages must be >= 1 when totalElements > 0: " + totalPages);
        }
        var meta = java.util.Map.of(
                "page", page,
                "size", size,
                "totalElements", totalElements,
                "totalPages", totalPages);
        return new ApiResponseWrapper<>(data, null, null, null, null, meta, Instant.now());
    }
}
