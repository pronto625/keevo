package com.keevo.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.assistant.chat.adapter.out.model.ScriptedAssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.4 — the parity corpus of {@code keevo/contracts/ai-assistant.json} (≥ 30 real questions of Bref Point's
 * guided topics) replayed through {@code POST /api/v1/ai/chat} with the scripted model on the fixture the contract
 * describes: for each question the expected tools are called (audit, channel ASSISTANT, outcome OK), every expected
 * figure appears exactly in the reply, and no reply carries an identifier.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AI assistant — parity corpus: tool calls and exact figures (21.4)")
class AiAssistantParityCorpusTest extends FullContextIntegrationTest {

    static final Path CONTRACT = Path.of("..", "contracts", "ai-assistant.json");
    static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry moduleRegistry;
    @Autowired AssistantModel assistantModel;

    private AssistantCorpusFixture fixture;
    private String schema;
    private String ownerToken;

    @AfterEach
    void tearDown() {
        ((ScriptedAssistantModel) assistantModel).reset();
        if (schema != null) {
            jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = (SELECT id FROM public.tenants WHERE "
                    + "schema_name = ?)", schema);
            moduleRegistry.evict(schema);
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("every corpus question: expected tools called, exact figures in the reply, no identifier")
    void corpus() throws Exception {
        JsonNode contract = objectMapper.readTree(Files.readString(CONTRACT));
        JsonNode corpus = contract.path("corpus");
        assertThat(corpus.size()).as("parity corpus size").isGreaterThanOrEqualTo(30);
        fixture();
        String today = LocalDate.now(KeevoZones.WAT).toString();
        List<String> failures = new ArrayList<>();
        for (JsonNode q : corpus) {
            ObjectNode rule = ((ObjectNode) q.deepCopy());
            String json = objectMapper.writeValueAsString(rule).replace("@today", today);
            ObjectNode script = objectMapper.createObjectNode();
            ((ArrayNode) script.putArray("rules")).add(objectMapper.readTree(json));
            script.put("fallback", "question non prévue");
            ((ScriptedAssistantModel) assistantModel).useScript(script);
            int calls = assistantCalls().size();

            MvcResult r = http(HttpMethod.POST, "/api/v1/ai/chat", Map.of("message", q.path("question").asText()));
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String id = q.path("id").asText();
            if (r.getResponse().getStatus() != 200) {
                failures.add(id + ": HTTP " + r.getResponse().getStatus() + " " + body);
                continue;
            }
            String reply = objectMapper.readTree(body).path("data").path("reply").asText();
            for (JsonNode expected : q.path("expect")) {
                if (!reply.contains(expected.asText())) {
                    failures.add(id + ": « " + expected.asText() + " » missing from « " + reply + " »");
                }
            }
            if (UUID_TEXT.matcher(reply).find()) {
                failures.add(id + ": identifier in « " + reply + " »");
            }
            List<String> expectedCalls = new ArrayList<>();
            q.path("steps").forEach(s -> expectedCalls.add(s.path("tool").asText() + ":OK"));
            List<String> made = assistantCalls();
            List<String> these = made.subList(calls, made.size());
            if (!these.equals(expectedCalls)) {
                failures.add(id + ": tools " + these + " instead of " + expectedCalls);
            }
        }
        assertThat(failures).as("parity corpus").isEmpty();
    }

    private void fixture() throws Exception {
        fixture = new AssistantCorpusFixture(mockMvc, objectMapper, jwtTokenProvider, tenantFactory, tx, jdbc).create();
        schema = fixture.schema;
        ownerToken = fixture.ownerToken;
    }

    private MvcResult http(HttpMethod method, String path, Object body) throws Exception {
        return fixture.http(ownerToken, method, path, body);
    }

    private List<String> assistantCalls() {
        return fixture.assistantCalls(schema);
    }
}
