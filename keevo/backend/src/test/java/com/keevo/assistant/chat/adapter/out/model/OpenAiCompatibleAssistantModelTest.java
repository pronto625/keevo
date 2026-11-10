package com.keevo.assistant.chat.adapter.out.model;

import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Message;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolResult;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("21.4 — OpenAiCompatibleAssistantModel: Spring AI mapping, tools declared, never executed by the model")
class OpenAiCompatibleAssistantModelTest {

    private final List<Prompt> prompts = new ArrayList<>();

    private ChatModel answering(AssistantMessage out) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(out)));
            }
        };
    }

    @Test
    @DisplayName("messages, tool calls and tool results mapped; tools declared with internal execution off")
    void mapping() {
        AssistantMessage out = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id9", "function", "unpaid_call_list", "{\"limit\":3}"))).build();
        OpenAiCompatibleAssistantModel model = new OpenAiCompatibleAssistantModel(answering(out), "openai", "gpt-x",
                true);
        AssistantExchange exchange = new AssistantExchange("Système", List.of(Message.user("Q1"),
                Message.assistant("R1"), Message.user("Q2"),
                Message.toolCalls(null, List.of(new ToolCall("id1", "list_stores", "{}"))),
                Message.toolResults(List.of(new ToolResult("id1", "list_stores", "[]")))),
                List.of(new ToolSpec("unpaid_call_list", "Liste d'appel", "{\"type\":\"object\"}")), 800);

        AssistantTurn turn = model.respond(exchange);

        assertThat(turn.toolCalls()).containsExactly(new ToolCall("id9", "unpaid_call_list", "{\"limit\":3}"));
        Prompt p = prompts.get(0);
        assertThat(p.getInstructions()).extracting(m -> m.getMessageType()).containsExactly(MessageType.SYSTEM,
                MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        assertThat(((AssistantMessage) p.getInstructions().get(4)).getToolCalls().get(0).name())
                .isEqualTo("list_stores");
        assertThat(((ToolResponseMessage) p.getInstructions().get(5)).getResponses().get(0).responseData())
                .isEqualTo("[]");
        OpenAiChatOptions options = (OpenAiChatOptions) p.getOptions();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getModel()).isEqualTo("gpt-x");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(800);
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("unpaid_call_list");
        assertThatThrownBy(() -> options.getToolCallbacks().get(0).call("{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("text answer; empty answer and provider exception → AssistantModelException (no content in message)")
    void answersAndFailures() {
        OpenAiCompatibleAssistantModel text = new OpenAiCompatibleAssistantModel(
                answering(new AssistantMessage("Bonjour")), "gemini", "gemini-2.0-flash", false);
        AssistantExchange q = new AssistantExchange("S", List.of(Message.user("Q")), List.of(), 100);
        assertThat(text.respond(q).text()).isEqualTo("Bonjour");
        assertThat(((OpenAiChatOptions) prompts.get(0).getOptions()).getMaxTokens()).isEqualTo(100);
        assertThat(prompts.get(0).getOptions()).extracting(o -> ((OpenAiChatOptions) o).getToolCallbacks())
                .satisfies(c -> assertThat(c).isNullOrEmpty());
        assertThat(text.title("Q")).isEqualTo("Bonjour");

        OpenAiCompatibleAssistantModel empty = new OpenAiCompatibleAssistantModel(
                answering(new AssistantMessage("  ")), "ollama", "llama3.2", false);
        assertThatThrownBy(() -> empty.respond(q)).isInstanceOf(AssistantModelException.class);

        OpenAiCompatibleAssistantModel failing = new OpenAiCompatibleAssistantModel(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("HTTP 500 secret-body");
            }
        }, "openai", "gpt-x", true);
        assertThatThrownBy(() -> failing.respond(q)).isInstanceOf(AssistantModelException.class)
                .hasMessage("openai call failed: IllegalStateException");
    }
}
