package com.keevo.assistant.chat.domain.port.out;

import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantTurn;

/**
 * Story 21.4 — the model provider behind the assistant (strategy chosen by {@code keevo.ai.provider}): Claude through
 * Anthropic's SDK (21.4.2), an OpenAI-compatible model (OpenAI, Gemini, Ollama), a scripted fake (dev / tests) or
 * nothing (the default). The model
 * never executes anything: it asks for tool calls, the assistant runs them on the request thread through the AI tool
 * registry and hands the results back.
 */
public interface AssistantModel {

    /** {@code false} when no provider is configured: free-text questions are refused, nothing is called. */
    boolean available();

    /**
     * One model turn.
     *
     * @throws AssistantModelException the provider failed, timed out or answered something unusable (the message never
     *                                 carries the question, the answer or a key)
     */
    AssistantTurn respond(AssistantExchange exchange);

    /**
     * A short title (a few words) for a conversation starting with {@code firstMessage}.
     *
     * @throws AssistantModelException the provider failed
     */
    String title(String firstMessage);
}
