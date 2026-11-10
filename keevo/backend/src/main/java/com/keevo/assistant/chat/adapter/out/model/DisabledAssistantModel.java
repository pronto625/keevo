package com.keevo.assistant.chat.adapter.out.model;

import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;

/**
 * Story 21.4 — no model provider configured ({@code keevo.ai.provider=disabled}, the default, or a provider without
 * its key): free-text questions answer 503 {@code AI_UNAVAILABLE} before anything is called or counted; guided topics
 * and conversations keep working.
 */
public class DisabledAssistantModel implements AssistantModel {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public AssistantTurn respond(AssistantExchange exchange) {
        throw new AssistantModelException("no AI provider configured");
    }

    @Override
    public String title(String firstMessage) {
        throw new AssistantModelException("no AI provider configured");
    }
}
