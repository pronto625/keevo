package com.keevo.shared.infrastructure.web;

/**
 * InvalidRequestParameterException — a query parameter is syntactically valid for Spring but
 * violates the endpoint contract (range, ordering, opaque cursor). Mapped by
 * {@link GlobalExceptionHandler} to HTTP 400 {@code VALIDATION_ERROR}, the same response as a
 * type mismatch on a typed parameter (Story v1s-18-6). Story 15.5: an optional French
 * {@code userMessage} replaces the generic « Paramètre invalide : … » text when the user can act on
 * the precise reason (e.g. a date range in the wrong order).
 */
public class InvalidRequestParameterException extends RuntimeException {

    private final String parameter;
    private final String userMessage;

    public InvalidRequestParameterException(String parameter, String message) {
        this(parameter, message, null);
    }

    /** @param userMessage French text shown to the user instead of « Paramètre invalide : parameter » */
    public InvalidRequestParameterException(String parameter, String message, String userMessage) {
        super(message);
        this.parameter = parameter;
        this.userMessage = userMessage;
    }

    /** French message for the user, or {@code null} for the generic one. */
    public String getUserMessage() {
        return userMessage;
    }

    public String getParameter() {
        return parameter;
    }
}
