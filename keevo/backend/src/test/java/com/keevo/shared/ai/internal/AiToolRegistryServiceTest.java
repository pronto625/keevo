package com.keevo.shared.ai.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.ai.AiToolResult.Outcome;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.application.ModulePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AI tool registry — discovery, listing, call outcomes, audit (Story 21.8)")
class AiToolRegistryServiceTest {

    static final String TENANT = "kv_abc123";
    static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    /** Fixture tools (called without proxy: only the registry's own pre-checks gate them here). */
    public static class FixtureMcpTools {

        public record Echo(String text, LocalDate day) {
        }

        @Tool(name = "echo", description = "Renvoie le texte")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public Echo echo(@ToolParam(description = "texte") String text,
                         @ToolParam(required = false, description = "jour") LocalDate day) {
            return new Echo(text, day);
        }

        @Tool(name = "owner_only", description = "Réservé")
        @PreAuthorize("hasRole('OWNER')")
        @RequiresEntitlement(value = "reporting", access = RequiresEntitlement.Access.READ)
        public String ownerOnly() {
            return "secret";
        }

        @Tool(name = "with_permission", description = "Permission")
        @RequiresPermission("FINANCE_VIEW")
        @RequiresEntitlement(value = "finance", access = RequiresEntitlement.Access.READ)
        public String withPermission() {
            return "ok";
        }

        @Tool(name = "invalid", description = "Paramètre refusé")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String invalid() {
            throw new InvalidRequestParameterException("from", "bad", "La date de début doit précéder la date de fin.");
        }

        @Tool(name = "domain_refusal", description = "Refus métier")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String domainRefusal() {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, "Le classement n'est pas partagé avec les employés.");
        }

        @Tool(name = "boom", description = "Erreur")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String boom() {
            throw new IllegalStateException("database down");
        }

        @Tool(name = "huge", description = "Trop gros")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String huge() {
            return "x".repeat(AiToolRegistryService.MAX_RESULT_CHARS + 10);
        }
    }

    public static class DuplicateMcpTools {
        @Tool(name = "echo", description = "Doublon")
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public String echo() {
            return "";
        }
    }

    EntitlementService entitlements = mock(EntitlementService.class);
    ModulePermissions permissions = mock(ModulePermissions.class);
    AiToolCallAudit audit = mock(AiToolCallAudit.class);
    AiToolRegistryService registry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ObjectProvider<EntitlementService> e = mock(ObjectProvider.class);
        when(e.getObject()).thenReturn(entitlements);
        ObjectProvider<ModulePermissions> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(permissions);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        registry = new AiToolRegistryService(mock(ListableBeanFactory.class), e, p, audit, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, AiToolRegistryService.Entry> table = new HashMap<>();
        registry.register(new FixtureMcpTools(), FixtureMcpTools.class, table);
        registry.replaceTools(table);
        when(entitlements.enabledModules(TENANT)).thenReturn(Set.of("crm", "reporting", "finance"));
        when(permissions.permissionsOf(anyCollection())).thenAnswer(inv ->
                inv.<java.util.Collection<String>>getArgument(0).contains("OWNER") ? Set.of("FINANCE_VIEW") : Set.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static UUID login(String role, UUID store) {
        UUID user = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new AuthDetails(null, store, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.setCurrentTenant(TENANT);
        return user;
    }

    @Test
    @DisplayName("discovery: name, French description, generated schema, gates read from the annotations")
    void discovery() {
        Map<String, AiToolDescriptor> tools = registry.descriptors();
        assertThat(tools).containsKeys("echo", "owner_only", "with_permission");
        AiToolDescriptor echo = tools.get("echo");
        assertThat(echo.description()).isEqualTo("Renvoie le texte");
        assertThat(echo.module()).isEqualTo("crm");
        assertThat(echo.inputSchema()).contains("\"text\"", "\"day\"", "\"required\"");
        assertThat(tools.get("owner_only").roleRule()).isEqualTo("hasRole('OWNER')");
        assertThat(tools.get("with_permission").permissions()).containsExactly("FINANCE_VIEW");
        assertThat(registry.allTools()).extracting(AiToolDescriptor::name).isSorted();
    }

    @Test
    @DisplayName("a duplicate tool name fails the startup")
    void duplicateName() {
        Map<String, AiToolRegistryService.Entry> table = new HashMap<>();
        registry.register(new FixtureMcpTools(), FixtureMcpTools.class, table);
        assertThatThrownBy(() -> registry.register(new DuplicateMcpTools(), DuplicateMcpTools.class, table))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate AI tool name 'echo'");
    }

    @Test
    @DisplayName("listing: role rule, permission and module state per session; nothing without a session")
    void listing() {
        assertThat(registry.allowedTools()).isEmpty();

        login("EMPLOYEE", UUID.randomUUID());
        assertThat(registry.allowedTools()).extracting(AiToolDescriptor::name)
                .contains("echo").doesNotContain("owner_only", "with_permission");

        login("OWNER", null);
        assertThat(registry.allowedTools()).extracting(AiToolDescriptor::name)
                .contains("echo", "owner_only", "with_permission");
        when(entitlements.enabledModules(TENANT)).thenReturn(Set.of("crm", "reporting"));
        assertThat(registry.allowedTools()).extracting(AiToolDescriptor::name).doesNotContain("with_permission");
        assertThat(registry.allowedToolCallbacks(AiToolChannel.ASSISTANT)).extracting(c -> c.getToolDefinition().name())
                .contains("owner_only").doesNotContain("with_permission");
    }

    @Test
    @DisplayName("an allowed call returns the JSON result and is audited OK with actor, tool, channel, duration")
    void okCall() {
        UUID actor = login("OWNER", null);
        AiToolResult result = registry.call("echo", "{\"text\":\"bonjour\",\"day\":\"2026-11-10\"}",
                AiToolChannel.ASSISTANT);
        assertThat(result.isOk()).isTrue();
        assertThat(result.json()).isEqualTo("{\"text\":\"bonjour\",\"day\":\"2026-11-10\"}");
        verify(entitlements).assertEnabled(TENANT, "crm");
        ArgumentCaptor<AiToolCallAudit.AiToolCall> call = ArgumentCaptor.forClass(AiToolCallAudit.AiToolCall.class);
        verify(audit).record(call.capture());
        assertThat(call.getValue()).extracting(AiToolCallAudit.AiToolCall::tenantSchema,
                        AiToolCallAudit.AiToolCall::actorId, AiToolCallAudit.AiToolCall::tool,
                        AiToolCallAudit.AiToolCall::channel, AiToolCallAudit.AiToolCall::outcome,
                        AiToolCallAudit.AiToolCall::errorCode, AiToolCallAudit.AiToolCall::calledAt)
                .containsExactly(TENANT, actor, "echo", AiToolChannel.ASSISTANT, Outcome.OK, null, NOW);
        assertThat(call.getValue().durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("refusals: role and permission → FORBIDDEN; module off → the entitlement code; audited REFUSED")
    void refusals() {
        login("EMPLOYEE", UUID.randomUUID());
        AiToolResult role = registry.call("owner_only", null, AiToolChannel.MCP);
        assertThat(role).extracting(AiToolResult::outcome, AiToolResult::errorCode, AiToolResult::message)
                .containsExactly(Outcome.REFUSED, "FORBIDDEN", "Vous n'avez pas les droits nécessaires");
        assertThat(registry.call("with_permission", "", AiToolChannel.MCP).errorCode()).isEqualTo("FORBIDDEN");

        login("OWNER", null);
        doThrow(new DomainException(ErrorCode.MODULE_NOT_ENTITLED, "Module finance not entitled"))
                .when(entitlements).assertEnabled(TENANT, "finance");
        AiToolResult module = registry.call("with_permission", "{}", AiToolChannel.MCP);
        assertThat(module.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(module.errorCode()).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(module.message()).isNotBlank().doesNotContain("finance not entitled");
        verify(audit, times(3)).record(any());
    }

    @Test
    @DisplayName("invalid: unknown tool, unparseable arguments, a REST-style 400 → INVALID with a French message")
    void invalid() {
        login("OWNER", null);
        assertThat(registry.call("nope", "{}", AiToolChannel.INTERNAL))
                .extracting(AiToolResult::outcome, AiToolResult::errorCode, AiToolResult::message)
                .containsExactly(Outcome.INVALID, "VALIDATION_ERROR", "Outil inconnu : nope");
        assertThat(registry.call("echo", "{\"day\":\"10/11/2026\"}", AiToolChannel.INTERNAL))
                .extracting(AiToolResult::outcome, AiToolResult::message)
                .containsExactly(Outcome.INVALID, AiToolRegistryService.INVALID_ARGUMENTS_MESSAGE);
        assertThat(registry.call("echo", "pas du json", AiToolChannel.INTERNAL).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(registry.call("invalid", "{}", AiToolChannel.INTERNAL).message())
                .isEqualTo("La date de début doit précéder la date de fin.");
        assertThat(registry.call("domain_refusal", "{}", AiToolChannel.INTERNAL))
                .extracting(AiToolResult::outcome, AiToolResult::message)
                .containsExactly(Outcome.REFUSED, "Le classement n'est pas partagé avec les employés.");
    }

    @Test
    @DisplayName("an unexpected failure → ERROR, generic French message, no internal detail; too large → refused")
    void errors() {
        login("OWNER", null);
        AiToolResult boom = registry.call("boom", "{}", AiToolChannel.INTERNAL);
        assertThat(boom).extracting(AiToolResult::outcome, AiToolResult::message)
                .containsExactly(Outcome.ERROR, AiToolRegistryService.ERROR_MESSAGE);
        assertThat(boom.message()).doesNotContain("database");
        assertThat(registry.call("huge", "{}", AiToolChannel.INTERNAL).errorCode()).isEqualTo("RESULT_TOO_LARGE");
    }

    @Test
    @DisplayName("no session (no actor or no tenant): refused, nothing invoked, nothing audited")
    void noSession() {
        assertThat(registry.call("echo", "{\"text\":\"x\"}", AiToolChannel.MCP))
                .extracting(AiToolResult::outcome, AiToolResult::message)
                .containsExactly(Outcome.REFUSED, AiToolRegistryService.NO_SESSION_MESSAGE);
        login("OWNER", null);
        TenantContext.clear();
        assertThat(registry.call("echo", "{\"text\":\"x\"}", AiToolChannel.MCP).outcome()).isEqualTo(Outcome.REFUSED);
        verify(audit, never()).record(any());
        verify(entitlements, never()).assertEnabled(any(), any());
    }

    @Test
    @DisplayName("an audit failure never fails the call")
    void auditFailure() {
        login("OWNER", null);
        doThrow(new IllegalStateException("db")).when(audit).record(any());
        assertThat(registry.call("echo", "{\"text\":\"x\"}", AiToolChannel.MCP).isOk()).isTrue();
    }

    @Test
    @DisplayName("Spring AI callback: the JSON result, or {\"erreur\": message} — never an exception")
    void callback() {
        login("EMPLOYEE", UUID.randomUUID());
        List<ToolCallback> callbacks = registry.allowedToolCallbacks(AiToolChannel.ASSISTANT);
        ToolCallback echo = callbacks.stream().filter(c -> c.getToolDefinition().name().equals("echo")).findFirst()
                .orElseThrow();
        assertThat(echo.call("{\"text\":\"salut\"}")).contains("\"salut\"");
        ToolCallback boom = new KeevoToolCallback(
                org.springframework.ai.tool.definition.ToolDefinition.builder().name("owner_only").description("x")
                        .inputSchema("{}").build(), registry, AiToolChannel.ASSISTANT, new ObjectMapper());
        assertThat(boom.call("{}")).isEqualTo("{\"erreur\":\"Vous n'avez pas les droits nécessaires\"}");
    }
}
