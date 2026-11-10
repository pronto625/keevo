package com.keevo.shared.ai.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Story 21.8 — a registry tool as a Spring AI {@link ToolCallback} (for 21.4's chat client and 21.9's MCP server).
 * Delegates to {@link AiToolRegistry#call}, so gates, scope and audit are those of the registry; never throws: a refusal
 * or an error is answered as {@code {"erreur": "<message en français>"}} so the model can relay it.
 */
final class KeevoToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final AiToolRegistry registry;
    private final AiToolChannel channel;
    private final ObjectMapper mapper;

    KeevoToolCallback(ToolDefinition definition, AiToolRegistry registry, AiToolChannel channel, ObjectMapper mapper) {
        this.definition = definition;
        this.registry = registry;
        this.channel = channel;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        AiToolResult result = registry.call(definition.name(), toolInput, channel);
        if (result.isOk()) {
            return result.json();
        }
        try {
            return mapper.writeValueAsString(Map.of("erreur", result.message()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"erreur\":\"Une erreur est survenue.\"}";
        }
    }

    /** The session is the calling thread's (security + tenant contexts): the tool context is not used. */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }
}
