package com.keevo.shared.infrastructure.web;

/**
 * InvalidRequestParameterException — a query parameter is syntactically valid for Spring but
 * violates the endpoint contract (range, ordering, opaque cursor). Mapped by
 * {@link GlobalExceptionHandler} to HTTP 400 {@code VALIDATION_ERROR}, the same response as a
 * type mismatch on a typed parameter (Story v1s-18-6).
 */
public class InvalidRequestParameterException extends RuntimeException {

    private final String parameter;

    public InvalidRequestParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public String getParameter() {
        return parameter;
    }
}
