package com.keevo.assistant.chat.domain.port.out;

/**
 * Story 21.4 — the model provider failed (unreachable, timeout, refusal, malformed answer). Its message is technical,
 * for the logs only, and never contains a question, an answer or a key.
 */
public class AssistantModelException extends RuntimeException {

    public AssistantModelException(String message) {
        super(message);
    }

    public AssistantModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
