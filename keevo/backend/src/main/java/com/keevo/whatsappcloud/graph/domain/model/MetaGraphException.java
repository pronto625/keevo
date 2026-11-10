package com.keevo.whatsappcloud.graph.domain.model;

/**
 * Story 21.5 — a failed call to Meta's Graph API, by what it means for the caller:
 * <ul>
 *   <li>{@link Kind#REFUSED} — Meta answered with an error: certainly not done. {@link #userMessage()} carries Meta's
 *       text for the user when it gave one ({@code error_user_title : error_user_msg}), else {@code null};</li>
 *   <li>{@link Kind#UNAVAILABLE} — Meta not reached (connection refused, not configured): certainly not done;</li>
 *   <li>{@link Kind#UNKNOWN} — the request went out without an answer (read timeout): maybe done, never repeated.</li>
 * </ul>
 * The message never carries a token, a phone number or a message body.
 */
public class MetaGraphException extends RuntimeException {

    public enum Kind { REFUSED, UNAVAILABLE, UNKNOWN }

    private final Kind kind;
    private final String userMessage;
    private final int metaCode;

    public MetaGraphException(Kind kind, String message, String userMessage, int metaCode) {
        super(message);
        this.kind = kind;
        this.userMessage = userMessage;
        this.metaCode = metaCode;
    }

    public static MetaGraphException unavailable(String message) {
        return new MetaGraphException(Kind.UNAVAILABLE, message, null, -1);
    }

    public static MetaGraphException unknown(String message) {
        return new MetaGraphException(Kind.UNKNOWN, message, null, -1);
    }

    public static MetaGraphException refused(String message, String userMessage, int metaCode) {
        return new MetaGraphException(Kind.REFUSED, message, userMessage, metaCode);
    }

    public Kind kind() {
        return kind;
    }

    /** Meta's text for the user, or {@code null}. */
    public String userMessage() {
        return userMessage;
    }

    /** Meta's numeric error code ({@code error.code}), {@code -1} when none. */
    public int metaCode() {
        return metaCode;
    }
}
