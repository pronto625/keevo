package com.keevo.assistant.chat.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Story 21.4 — one request to the model: the system prompt, the conversation so far (questions, answers, tool calls
 * and their results) and the tools it may call.
 *
 * @param maxOutputTokens cap on the generated text of this turn
 * @param answerOnly      Story 21.4.2 — the model must answer in text now: the tools stay declared (a conversation
 *                        holding tool calls must keep its tools, and the cached prefix stays the same) but may not be
 *                        called ({@code tool_choice: none}); an adapter whose provider cannot say so drops them
 * @param timeLeft        Story 21.4.2 — what is left of the question's deadline: a provider call (retry included) never
 *                        waits longer; {@code null} = the adapter's own timeout
 */
public record AssistantExchange(String system, List<Message> messages, List<ToolSpec> tools, int maxOutputTokens,
                                boolean answerOnly, Duration timeLeft) {

    public AssistantExchange {
        Objects.requireNonNull(system, "system");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }

    /** A turn that may call {@code tools} (an empty list = text only), with the adapter's own timeout. */
    public AssistantExchange(String system, List<Message> messages, List<ToolSpec> tools, int maxOutputTokens) {
        this(system, messages, tools, maxOutputTokens, false, null);
    }

    /** Whether the model may call a tool in this turn. */
    public boolean toolsCallable() {
        return !answerOnly && !tools.isEmpty();
    }

    /** Who wrote a message. */
    public enum Role { USER, ASSISTANT, TOOL }

    /**
     * One message: a question ({@code USER}, text), an answer or a tool request ({@code ASSISTANT}, text and/or tool
     * calls), or the results of the requested tools ({@code TOOL}).
     */
    public record Message(Role role, String text, List<ToolCall> toolCalls, List<ToolResult> toolResults) {

        public Message {
            Objects.requireNonNull(role, "role");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        }

        public static Message user(String text) {
            return new Message(Role.USER, text, List.of(), List.of());
        }

        public static Message assistant(String text) {
            return new Message(Role.ASSISTANT, text, List.of(), List.of());
        }

        public static Message toolCalls(String text, List<ToolCall> calls) {
            return new Message(Role.ASSISTANT, text, calls, List.of());
        }

        public static Message toolResults(List<ToolResult> results) {
            return new Message(Role.TOOL, null, List.of(), results);
        }
    }

    /** A tool the model may call: what the model sees of an AI tool (21.8). */
    public record ToolSpec(String name, String description, String inputSchema) {
    }

    /** A tool call requested by the model ({@code arguments}: JSON object). */
    public record ToolCall(String id, String name, String arguments) {
    }

    /** The result handed back to the model for one call ({@code content}: JSON, possibly truncated). */
    public record ToolResult(String callId, String name, String content) {
    }
}
