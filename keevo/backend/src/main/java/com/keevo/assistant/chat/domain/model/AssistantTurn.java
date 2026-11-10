package com.keevo.assistant.chat.domain.model;

import java.util.List;

/**
 * Story 21.4 — what the model returned for one exchange: tool calls to run (then another exchange), or the final text.
 */
public record AssistantTurn(String text, List<AssistantExchange.ToolCall> toolCalls) {

    public AssistantTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AssistantTurn answer(String text) {
        return new AssistantTurn(text, List.of());
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
