package com.keevo.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("21.9 — MCP handler: methods, scope ∩ rights, quota counted per served call, errors never thrown")
class KeevoMcpServerHandlerTest {

    static final String TENANT = "kv_abc123";

    private AiToolRegistry tools;
    private UsageQuotaPort quota;
    private KeevoMcpServerHandler handler;
    private final AtomicInteger committed = new AtomicInteger();
    private final AtomicInteger rolledBack = new AtomicInteger();
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        tools = mock(AiToolRegistry.class);
        quota = mock(UsageQuotaPort.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        doAnswer(i -> {
            if (((TransactionStatus) i.getArgument(0)).isRollbackOnly()) {
                rolledBack.incrementAndGet();
            } else {
                committed.incrementAndGet();
            }
            return null;
        }).when(tm).commit(any());
        when(tools.allowedTools()).thenReturn(List.of(
                tool("list_stores"), tool("revenue_summary"), tool("financial_result")));
        handler = new KeevoMcpServerHandler(tools, quota, new TransactionTemplate(tm),
                new JacksonMcpJsonMapper(new ObjectMapper()));
        context = McpTransportContext.create(Map.of(McpRequestSession.ATTRIBUTE,
                new McpRequestSession(UUID.randomUUID(), Set.of("list_stores", "revenue_summary", "stock_by_store"),
                        null)));
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AiToolDescriptor tool(String name) {
        return new AiToolDescriptor(name, "Description de " + name, "{\"type\":\"object\",\"properties\":{}}",
                "store", List.of(), "hasRole('OWNER')", "X#y");
    }

    private McpSchema.JSONRPCResponse send(String method, Object params) {
        return handler.handleRequest(context, new McpSchema.JSONRPCRequest("2.0", method, 7, params)).block();
    }

    @Test
    @DisplayName("tools/list = token scope ∩ the owner's allowed tools (a scoped tool no longer allowed is hidden)")
    void listIsIntersection() {
        McpSchema.ListToolsResult result = (McpSchema.ListToolsResult) send("tools/list", null).result();
        assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("list_stores", "revenue_summary");
        assertThat(result.tools().get(0).annotations().readOnlyHint()).isTrue();
    }

    @Test
    @DisplayName("tools/call served: quota checked (rolled back) then one credit committed; text = the tool's JSON")
    void served() {
        when(tools.call("list_stores", "{}", AiToolChannel.MCP)).thenReturn(AiToolResult.ok("{\"stores\":[]}"));
        McpSchema.JSONRPCResponse r = send("tools/call", Map.of("name", "list_stores", "arguments", Map.of()));
        McpSchema.CallToolResult result = (McpSchema.CallToolResult) r.result();
        assertThat(result.isError()).isFalse();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("{\"stores\":[]}");
        assertThat(r.id()).isEqualTo(7);
        assertThat(rolledBack.get()).isEqualTo(1);
        assertThat(committed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("out of scope: refused without calling the registry nor touching the quota")
    void outOfScope() {
        McpSchema.CallToolResult result = (McpSchema.CallToolResult) send("tools/call",
                Map.of("name", "financial_result")).result();
        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("n'est pas autorisé");
        verify(tools, never()).call(anyString(), any(), any());
        assertThat(rolledBack.get() + committed.get()).isZero();
    }

    @Test
    @DisplayName("tool refused: the French message, nothing counted")
    void refused() {
        when(tools.call(eq("revenue_summary"), any(), eq(AiToolChannel.MCP))).thenReturn(AiToolResult.failure(
                AiToolResult.Outcome.REFUSED, "MODULE_NOT_ENTITLED", "Ce module n'est pas inclus dans votre abonnement"));
        McpSchema.CallToolResult result = (McpSchema.CallToolResult) send("tools/call",
                Map.of("name", "revenue_summary", "arguments", Map.of("from", "2026-11-01"))).result();
        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("abonnement");
        assertThat(committed.get()).isZero();
    }

    @Test
    @DisplayName("quota exhausted: refused in French before the tool runs")
    void quotaExhausted() {
        doThrow(new DomainException(ErrorCode.QUOTA_EXCEEDED, "AI_CREDITS DAY quota reached"))
                .when(quota).assertAndIncrementForSchema(TENANT, UsageMetric.AI_CREDITS, 1);
        McpSchema.CallToolResult result = (McpSchema.CallToolResult) send("tools/call",
                Map.of("name", "list_stores")).result();
        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("Quota de votre offre atteint");
        verify(tools, never()).call(anyString(), any(), any());
    }

    @Test
    @DisplayName("initialize, ping, unknown method, bad params, missing session, unexpected failure → never an exception")
    void protocolEdges() {
        McpSchema.InitializeResult init = (McpSchema.InitializeResult) send("initialize",
                Map.of("protocolVersion", "2025-03-26")).result();
        assertThat(init.protocolVersion()).isEqualTo("2025-03-26");
        assertThat(init.serverInfo().name()).isEqualTo("keevo");
        assertThat(((McpSchema.InitializeResult) send("initialize", null).result()).protocolVersion())
                .isEqualTo("2025-11-25");
        assertThat(send("ping", null).error()).isNull();
        assertThat(send("prompts/list", null).error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
        assertThat(send("tools/call", Map.of("arguments", Map.of())).error().code())
                .isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
        assertThat(send("tools/call", "not an object").error().code())
                .isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);

        when(tools.allowedTools()).thenThrow(new IllegalStateException("boom"));
        McpSchema.JSONRPCResponse failed = send("tools/list", null);
        assertThat(failed.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
        assertThat(failed.error().message()).doesNotContain("boom");

        context = McpTransportContext.EMPTY;
        assertThat(send("tools/list", null).error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("credits used up (or counter failing) during the call: the served result is still returned")
    void consumeRaceServesTheResult() {
        when(tools.call("list_stores", "{}", AiToolChannel.MCP)).thenReturn(AiToolResult.ok("{\"stores\":[]}"));
        org.mockito.Mockito.doNothing()
                .doThrow(new DomainException(ErrorCode.QUOTA_EXCEEDED, "AI_CREDITS DAY quota reached"))
                .doNothing()
                .doThrow(new IllegalStateException("counter down"))
                .when(quota).assertAndIncrementForSchema(TENANT, UsageMetric.AI_CREDITS, 1);
        for (int i = 0; i < 2; i++) {
            McpSchema.CallToolResult result = (McpSchema.CallToolResult) send("tools/call",
                    Map.of("name", "list_stores", "arguments", Map.of())).result();
            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("{\"stores\":[]}");
        }
    }
}
