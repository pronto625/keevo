package com.keevo.shared.domain.exception;

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
 * }</pre>
 */
public class DomainException extends RuntimeException {

    private final String domainCode;

    // ── String-based constructors (backward compatible) ────────────

    public DomainException(String domainCode) {
        super(domainCode);
        this.domainCode = domainCode;
    }

    public DomainException(String domainCode, String message) {
        super(domainCode + ": " + message);
        this.domainCode = domainCode;
    }

    public DomainException(String domainCode, Throwable cause) {
        super(domainCode, cause);
        this.domainCode = domainCode;
    }

    // ── ErrorCode-based constructors (type-safe, preferred) ──────

    public DomainException(ErrorCode code) {
        super(code.name());
        this.domainCode = code.name();
    }

    public DomainException(ErrorCode code, String message) {
        super(code.name() + ": " + message);
        this.domainCode = code.name();
    }

    public DomainException(ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.domainCode = code.name();
    }

    public String getDomainCode() {
        return domainCode;
    }
}
