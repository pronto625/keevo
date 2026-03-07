package com.keevo.shared.domain.exception;

import java.util.Map;

/**
 * DomainException — Base exception for all domain rule violations.
 *
 * <p>Pure Java — NO Spring/framework imports. Every domain layer
 * exception must extend this class or throw it directly.
 *
 * <p>Usage:
 * <pre>{@code
 *   throw new DomainException("INVALID_AMOUNT");
 *   throw new DomainException("PRODUCT_NOT_FOUND", "productId", id);
 *   throw new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "limit", Map.of("entity","stores"));
 * }</pre>
 */
public class DomainException extends RuntimeException {

    private final String domainCode;
    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    // ── String-based constructors (backward compatible) ────────────

    public DomainException(String domainCode) {
        super(domainCode);
        this.domainCode = domainCode;
        this.errorCode = null;
        this.details = null;
    }

    public DomainException(String domainCode, String message) {
        super(domainCode + ": " + message);
        this.domainCode = domainCode;
        this.errorCode = null;
        this.details = null;
    }

    public DomainException(String domainCode, Throwable cause) {
        super(domainCode, cause);
        this.domainCode = domainCode;
        this.errorCode = null;
        this.details = null;
    }

    // ── ErrorCode-based constructors (type-safe, preferred) ──────

    public DomainException(ErrorCode code) {
        super(code.name());
        this.domainCode = code.name();
        this.errorCode = code;
        this.details = null;
    }

    public DomainException(ErrorCode code, String message) {
        super(code.name() + ": " + message);
        this.domainCode = code.name();
        this.errorCode = code;
        this.details = null;
    }

    public DomainException(ErrorCode code, String message, Map<String, Object> details) {
        super(code.name() + ": " + message);
        this.domainCode = code.name();
        this.errorCode = code;
        this.details = details;
    }

    public DomainException(ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.domainCode = code.name();
        this.errorCode = code;
        this.details = null;
    }

    public String getDomainCode() {
        return domainCode;
    }

    /** Type-safe accessor — present when constructed with an {@link ErrorCode} constant. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Optional structured details (entity, limit, current, etc.). May be null. */
    public Map<String, Object> getDetails() {
        return details;
    }
}
