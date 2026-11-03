package com.keevo.shared.module;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

/**
 * Story 16.12 — the tenant's foundation tables could not be brought up to date right now ({@code SERVICE_UNAVAILABLE},
 * HTTP 503 with the French catalogue message): the provisioning failed, is backing off after earlier failures, or
 * another provisioning of the same tenant holds it. The client should retry after {@link #retryAfterSeconds()}.
 */
public class FoundationUnavailableException extends DomainException {

    private final long retryAfterSeconds;

    public FoundationUnavailableException(String message, long retryAfterSeconds) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** Seconds before a retry is worth it ({@code Retry-After}). */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
