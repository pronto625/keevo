package com.keevo.assistant.chat.domain.port.out;

/**
 * Story 21.4 — the model provider failed (unreachable, timeout, HTTP error) or answered something unusable (refusal,
 * answer cut by its token cap, malformed answer). Its message is technical, for the logs only, and never contains a
 * question, an answer or a key.
 */
public class AssistantModelException extends RuntimeException {

    private final boolean unusableAnswer;

    public AssistantModelException(String message) {
        this(message, null, false);
    }

    public AssistantModelException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private AssistantModelException(String message, Throwable cause, boolean unusableAnswer) {
        super(message, cause);
        this.unusableAnswer = unusableAnswer;
    }

    /**
     * Story 21.4.2 — the provider answered, but the answer cannot be used (refusal, cut by {@code max_tokens}):
     * retrying the same question gets the same result, so it is not reported as an outage.
     */
    public static AssistantModelException unusableAnswer(String message) {
        return new AssistantModelException(message, null, true);
    }

    /** {@code true}: the model answered unusably ({@code AI_ANSWER_FAILED}); {@code false}: the provider failed. */
    public boolean unusableAnswer() {
        return unusableAnswer;
    }
}
