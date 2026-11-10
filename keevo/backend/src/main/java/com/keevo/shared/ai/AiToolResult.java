package com.keevo.shared.ai;

/**
 * Story 21.8 — the outcome of one tool call: the JSON result, or a refusal / error with a French message that can be
 * shown to the user or handed to a model. Never an exception for the caller.
 *
 * @param outcome   OK, REFUSED (role, permission or module gate), INVALID (unknown tool, bad arguments, business
 *                  validation) or ERROR (unexpected failure)
 * @param json      the result serialised as JSON when {@code OK}, else {@code null}
 * @param errorCode the domain code ({@code FORBIDDEN}, {@code MODULE_NOT_ENTITLED}, {@code VALIDATION_ERROR}…) when
 *                  not OK, else {@code null}
 * @param message   French message when not OK, else {@code null}
 */
public record AiToolResult(Outcome outcome, String json, String errorCode, String message) {

    /** Outcome of a call, as stored in {@code public.ai_tool_calls.outcome}. */
    public enum Outcome { OK, REFUSED, INVALID, ERROR }

    public static AiToolResult ok(String json) {
        return new AiToolResult(Outcome.OK, json, null, null);
    }

    public static AiToolResult failure(Outcome outcome, String errorCode, String message) {
        return new AiToolResult(outcome, null, errorCode, message);
    }

    public boolean isOk() {
        return outcome == Outcome.OK;
    }
}
