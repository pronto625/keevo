package com.keevo.assistant.chat.adapter.out.model;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolResult;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("21.4.2 — AnthropicAssistantModel: Messages API request shape and answer reading (no network)")
class AnthropicAssistantModelTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"from":
            {"type":"string","description":"Début AAAA-MM-JJ"},"limit":{"type":"integer"}},"required":["from"],
            "additionalProperties":false}""";

    private final List<MessageCreateParams> sent = new ArrayList<>();

    private static Message message(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Message reply(String stopReason, String content) {
        return message("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":%s,
                "stop_reason":"%s","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
                .formatted(content, stopReason));
    }

    private AnthropicAssistantModel model(Message answer) {
        Function<MessageCreateParams, Message> call = p -> {
            sent.add(p);
            return answer;
        };
        return new AnthropicAssistantModel("claude-haiku-5-5", MAPPER, call, call);
    }

    private static JsonNode body(MessageCreateParams params) throws Exception {
        return MAPPER.readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
    }

    @Test
    @DisplayName("request: system, history, tool_use / tool_result round-trip, tools from the registry schema; no "
            + "sampling parameter, thinking disabled, effort low")
    void requestShape() throws Exception {
        AnthropicAssistantModel model = model(reply("tool_use", """
                [{"type":"thinking","thinking":"","signature":"sig"},
                 {"type":"text","text":"Je regarde."},
                 {"type":"tool_use","id":"toolu_9","name":"revenue_summary","input":{"from":"2026-10-01","limit":3}}]"""));
        AssistantExchange exchange = new AssistantExchange("Système", List.of(
                AssistantExchange.Message.user("Q1"), AssistantExchange.Message.assistant("R1"),
                AssistantExchange.Message.user("Q2"),
                AssistantExchange.Message.toolCalls(null, List.of(new ToolCall("toolu_1", "list_stores", "{}"))),
                AssistantExchange.Message.toolResults(List.of(new ToolResult("toolu_1", "list_stores", "[]")))),
                List.of(new ToolSpec("revenue_summary", "Chiffre d'affaires", SCHEMA)), 800);

        AssistantTurn turn = model.respond(exchange);

        assertThat(turn.text()).isEqualTo("Je regarde.");
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).id()).isEqualTo("toolu_9");
        assertThat(MAPPER.readTree(turn.toolCalls().get(0).arguments()))
                .isEqualTo(MAPPER.readTree("{\"from\":\"2026-10-01\",\"limit\":3}"));

        JsonNode body = body(sent.get(0));
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(800);
        assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("Système");
        assertThat(body.path("system").get(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.has("temperature") || body.has("top_p") || body.has("top_k")).isFalse();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(5);
        assertThat(messages.findValuesAsText("role")).containsExactly("user", "assistant", "user", "assistant",
                "user");
        JsonNode toolUse = messages.get(3).path("content").get(0);
        assertThat(toolUse.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.path("id").asText()).isEqualTo("toolu_1");
        assertThat(toolUse.path("input").isObject()).isTrue();
        JsonNode toolResult = messages.get(4).path("content").get(0);
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(toolResult.path("content").asText()).isEqualTo("[]");
        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("revenue_summary");
        assertThat(tool.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(tool.path("input_schema").path("properties").path("from").path("description").asText())
                .isEqualTo("Début AAAA-MM-JJ");
        assertThat(tool.path("input_schema").path("required").get(0).asText()).isEqualTo("from");
        assertThat(tool.path("input_schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(tool.path("input_schema").has("$schema")).isFalse();
    }

    @Test
    @DisplayName("text answer read by block type (thinking first tolerated); no tools offered → none declared; title")
    void textAndTitle() throws Exception {
        AnthropicAssistantModel model = model(reply("end_turn", """
                [{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":"Bonjour **150 000 FCFA**"}]"""));
        AssistantExchange q = new AssistantExchange("S", List.of(AssistantExchange.Message.user("Q")), List.of(), 100);
        assertThat(model.respond(q).text()).isEqualTo("Bonjour **150 000 FCFA**");
        assertThat(body(sent.get(0)).has("tools")).isFalse();
        assertThat(model.title("Mon chiffre ?")).isEqualTo("Bonjour **150 000 FCFA**");
        assertThat(body(sent.get(1)).path("max_tokens").asInt()).isEqualTo(AnthropicAssistantModel.TITLE_TOKENS);
    }

    @Test
    @DisplayName("refusal, empty answer, cut tool call and SDK failure → AssistantModelException without any content")
    void failures() {
        AssistantExchange q = new AssistantExchange("S", List.of(AssistantExchange.Message.user("Q")), List.of(), 100);
        assertThatThrownBy(() -> model(reply("refusal", "[]")).respond(q))
                .isInstanceOf(AssistantModelException.class).hasMessage("anthropic: refusal");
        assertThatThrownBy(() -> model(reply("end_turn", "[{\"type\":\"text\",\"text\":\"  \"}]")).respond(q))
                .isInstanceOf(AssistantModelException.class).hasMessageStartingWith("anthropic: empty answer");
        assertThatThrownBy(() -> model(reply("max_tokens", """
                [{"type":"tool_use","id":"t","name":"list_stores","input":{}}]""")).respond(q))
                .isInstanceOf(AssistantModelException.class).hasMessageContaining("max_tokens");
        AnthropicAssistantModel failing = new AnthropicAssistantModel("m", MAPPER, p -> {
            throw new AnthropicIoException("timeout reading secret-body");
        }, p -> {
            throw new IllegalStateException("x");
        });
        assertThatThrownBy(() -> failing.respond(q)).isInstanceOf(AssistantModelException.class)
                .hasMessage("anthropic call failed: AnthropicIoException");
        assertThatThrownBy(() -> failing.title("Q")).isInstanceOf(AssistantModelException.class)
                .hasMessage("anthropic call failed: IllegalStateException");
    }
}
