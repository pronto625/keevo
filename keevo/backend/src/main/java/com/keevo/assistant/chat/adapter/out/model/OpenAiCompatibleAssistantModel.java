package com.keevo.assistant.chat.adapter.out.model;

import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Message;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 21.4 — the assistant's model through Spring AI's OpenAI chat model, which speaks the OpenAI « chat
 * completions » protocol that OpenAI, Gemini (OpenAI-compatible endpoint) and Ollama all serve — the same choice as
 * Bref (OpenAI SDK + base URL). Internal tool execution is off: the tools are declared to the model (definitions
 * only) and the assistant runs the requested calls itself, on the request thread, through the AI tool registry.
 *
 * <p>Any failure of the provider (network, timeout, HTTP error after the retry, empty answer) becomes an
 * {@link AssistantModelException} whose message names the provider and the exception type only.
 */
public class OpenAiCompatibleAssistantModel implements AssistantModel {

    static final double TEMPERATURE = 0.2;
    static final int TITLE_TOKENS = 20;
    static final String TITLE_PROMPT = "Tu génères des titres courts (6 mots au plus) pour des conversations sur "
            + "l'activité d'un commerce. Réponds uniquement avec le titre, sans guillemets ni ponctuation finale.";

    private final ChatModel chat;
    private final ChatModel titles;
    private final String provider;
    private final String model;
    private final boolean completionTokens;

    /**
     * @param completionTokens {@code true}: cap with {@code max_completion_tokens} (OpenAI); {@code false}:
     *                         {@code max_tokens} (Gemini, Ollama)
     */
    public OpenAiCompatibleAssistantModel(ChatModel chat, String provider, String model, boolean completionTokens) {
        this(chat, chat, provider, model, completionTokens);
    }

    /** @param titles the model used for conversation titles (short timeout, no retry) */
    public OpenAiCompatibleAssistantModel(ChatModel chat, ChatModel titles, String provider, String model,
                                          boolean completionTokens) {
        this.chat = chat;
        this.titles = titles;
        this.provider = provider;
        this.model = model;
        this.completionTokens = completionTokens;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AssistantTurn respond(AssistantExchange exchange) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(exchange.system()));
        for (Message m : exchange.messages()) {
            messages.add(toSpring(m));
        }
        OpenAiChatOptions.Builder options = options(exchange.maxOutputTokens());
        if (!exchange.tools().isEmpty()) {
            options.toolCallbacks(exchange.tools().stream().map(OpenAiCompatibleAssistantModel::declaration).toList());
        }
        ChatResponse response = call(chat, new Prompt(messages, options.build()));
        List<ToolCall> calls = new ArrayList<>();
        String text = null;
        for (Generation g : response.getResults()) {
            AssistantMessage out = g.getOutput();
            if (out == null) {
                continue;
            }
            for (AssistantMessage.ToolCall c : out.getToolCalls()) {
                calls.add(new ToolCall(c.id(), c.name(), c.arguments()));
            }
            if (text == null && out.getText() != null && !out.getText().isBlank()) {
                text = out.getText();
            }
        }
        if (calls.isEmpty() && text == null) {
            throw new AssistantModelException(provider + ": empty answer");
        }
        return new AssistantTurn(text, calls);
    }

    @Override
    public String title(String firstMessage) {
        ChatResponse response = call(titles, new Prompt(List.of(new SystemMessage(TITLE_PROMPT),
                new UserMessage(firstMessage)), options(TITLE_TOKENS).build()));
        Generation g = response.getResult();
        String text = g == null || g.getOutput() == null ? null : g.getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AssistantModelException(provider + ": empty title");
        }
        return text;
    }

    private OpenAiChatOptions.Builder options(int maxTokens) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model(model).temperature(TEMPERATURE)
                .internalToolExecutionEnabled(false);
        return completionTokens ? b.maxCompletionTokens(maxTokens) : b.maxTokens(maxTokens);
    }

    private ChatResponse call(ChatModel model, Prompt prompt) {
        ChatResponse response;
        try {
            response = model.call(prompt);
        } catch (RuntimeException e) {
            throw new AssistantModelException(provider + " call failed: " + e.getClass().getSimpleName(), e);
        }
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new AssistantModelException(provider + ": no result");
        }
        return response;
    }

    static org.springframework.ai.chat.messages.Message toSpring(Message m) {
        return switch (m.role()) {
            case USER -> new UserMessage(m.text() == null ? "" : m.text());
            case ASSISTANT -> AssistantMessage.builder().content(m.text())
                    .toolCalls(m.toolCalls().stream()
                            .map(c -> new AssistantMessage.ToolCall(c.id(), "function", c.name(), c.arguments()))
                            .toList())
                    .build();
            case TOOL -> ToolResponseMessage.builder().responses(m.toolResults().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(r.callId(), r.name(), r.content()))
                            .toList())
                    .build();
        };
    }

    /** A definition-only callback: declares the tool to the model; never executed (internal execution is off). */
    static ToolCallback declaration(ToolSpec spec) {
        ToolDefinition definition = ToolDefinition.builder().name(spec.name()).description(spec.description())
                .inputSchema(spec.inputSchema()).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("tool " + spec.name() + " is executed by the assistant, not the model");
            }
        };
    }
}
