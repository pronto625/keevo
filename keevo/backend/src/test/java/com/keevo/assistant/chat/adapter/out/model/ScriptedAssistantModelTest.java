package com.keevo.assistant.chat.adapter.out.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Message;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolResult;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("21.4 — ScriptedAssistantModel: one tool per turn, chained arguments, figures in the answer")
class ScriptedAssistantModelTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<ToolSpec> TOOLS = List.of(new ToolSpec("list_stores", "", "{}"));

    private static ScriptedAssistantModel model(String json) throws Exception {
        return new ScriptedAssistantModel(MAPPER.readTree(json), MAPPER);
    }

    private static AssistantExchange exchange(List<Message> messages, List<ToolSpec> tools) {
        return new AssistantExchange("system", messages, tools, 100);
    }

    @Test
    @DisplayName("steps then answer; a placeholder picks an array element by field; numbers grouped à la française")
    void chainedSteps() throws Exception {
        ScriptedAssistantModel m = model("""
                {"rules":[{"question":"CA de Bonapriso ?","steps":[{"tool":"list_stores","args":{}},
                {"tool":"revenue_summary","args":{"storeId":"{{list_stores:/[name=Bonapriso]/storeId}}"}}],
                "answer":"**{{revenue_summary:/revenue}} FCFA** ({{revenue_summary:/pct}} %)"}],"fallback":"?"}""");
        List<Message> conv = new ArrayList<>(List.of(Message.user("  ca de   BONAPRISO ? ")));
        AssistantTurn t1 = m.respond(exchange(conv, TOOLS));
        assertThat(t1.toolCalls()).extracting(ToolCall::name).containsExactly("list_stores");
        conv.add(Message.toolCalls(null, t1.toolCalls()));
        conv.add(Message.toolResults(List.of(new ToolResult("call_0", "list_stores",
                "[{\"name\":\"Akwa\",\"storeId\":\"a\"},{\"name\":\"Bonapriso\",\"storeId\":\"b-1\"}]"))));
        AssistantTurn t2 = m.respond(exchange(conv, TOOLS));
        assertThat(t2.toolCalls().get(0).arguments()).isEqualTo("{\"storeId\":\"b-1\"}");
        conv.add(Message.toolCalls(null, t2.toolCalls()));
        conv.add(Message.toolResults(List.of(new ToolResult("call_1", "revenue_summary",
                "{\"revenue\":1250000,\"pct\":42.9}"))));
        assertThat(m.respond(exchange(conv, TOOLS)).text()).isEqualTo("**1 250 000 FCFA** (42,9 %)");
    }

    @Test
    @DisplayName("keywords, fallback, refusal relayed, last round without tools answers, scripted failure, swap")
    void otherPaths() throws Exception {
        ScriptedAssistantModel m = model("""
                {"rules":[{"keywords":["impay"],"steps":[{"tool":"unpaid_call_list"}],"answer":"{{unpaid_call_list:/totalDue}}"},
                {"keywords":["panne"],"fail":true}],"fallback":"Question non prévue"}""");
        assertThat(m.respond(exchange(List.of(Message.user("Bonjour")), TOOLS)).text()).isEqualTo("Question non prévue");
        List<Message> conv = new ArrayList<>(List.of(Message.user("Mes impayés ?")));
        conv.add(Message.toolCalls(null, List.of(new ToolCall("call_0", "unpaid_call_list", "{}"))));
        conv.add(Message.toolResults(List.of(new ToolResult("call_0", "unpaid_call_list",
                "{\"erreur\":\"Ce module n'est pas inclus dans votre abonnement\"}"))));
        assertThat(m.respond(exchange(conv, TOOLS)).text())
                .isEqualTo("Je ne peux pas répondre : Ce module n'est pas inclus dans votre abonnement");
        assertThat(m.respond(exchange(List.of(Message.user("Mes impayés ?")), List.of())).text()).isEqualTo("—");
        assertThatThrownBy(() -> m.respond(exchange(List.of(Message.user("en panne")), TOOLS)))
                .isInstanceOf(AssistantModelException.class);

        m.useScript(MAPPER.readTree("{\"rules\":[],\"fallback\":\"autre script\"}"));
        assertThat(m.respond(exchange(List.of(Message.user("Mes impayés ?")), TOOLS)).text()).isEqualTo("autre script");
        m.reset();
        assertThat(m.respond(exchange(List.of(Message.user("Mes impayés ?")), TOOLS)).wantsTools()).isTrue();
        assertThat(m.title("Quel est mon chiffre d'affaires du mois dernier ?")).isEqualTo("Quel est mon chiffre d'affaires du");
    }

    @Test
    @DisplayName("format: thousands with spaces, decimal comma, negative")
    void format() {
        assertThat(ScriptedAssistantModel.format(new BigDecimal("-25000.00"))).isEqualTo("-25 000");
        assertThat(ScriptedAssistantModel.format(new BigDecimal("200.0"))).isEqualTo("200");
        assertThat(ScriptedAssistantModel.format(new BigDecimal("42.857"))).isEqualTo("42,86");
    }
}
