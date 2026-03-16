package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuditControllerTest — @WebMvcTest slice tests for AuditController.
 *
 * <p>TDD RED → GREEN:
 * - RED: AuditController doesn't exist yet — fails to compile.
 * - GREEN: After implementing AuditController and wiring AuditPort mock.
 *
 * <p>JwtAuthFilter is mocked as pass-through for authenticated tests.
 * Unauthenticated test verifies 401 when filter is not bypassed.
 */
@WebMvcTest(AuditController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AuditController")
class AuditControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuditPort     auditPort;
    @MockBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void letFilterPassThrough() throws Exception {
        doAnswer(inv -> {
            // Populate SecurityContext with an authenticated user — Spring Security requires this
            var auth = new UsernamePasswordAuthenticationToken(
                    ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    // ══════════════════════════════════════════════════
    // GET /api/v1/audit — with both entityType and entityId
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/audit?entityType=Product&entityId={uuid}")
    class GetAuditBothFilters {

        @Test
        @DisplayName("→ 200 with filtered list of AuditEntryResponse")
        void returns200WithFilteredList() throws Exception {
            UUID entityId = UUID.randomUUID();
            UUID userId   = UUID.randomUUID();
            Instant now   = Instant.now();

            when(auditPort.findByEntityTypeAndEntityId("Product", entityId, 0, 20))
                    .thenReturn(new AuditPort.AuditPage(List.of(new AuditPort.AuditEntryRecord(
                            UUID.randomUUID(), "Product", entityId, "PRODUCT_CREATED",
                            null, "{\"name\":\"test\"}", userId, "+237690000001", now)), false));

            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "Product")
                            .param("entityId", entityId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(1)))
                    .andExpect(jsonPath("$.data.entries[0].entityType").value("Product"))
                    .andExpect(jsonPath("$.data.entries[0].entityId").value(entityId.toString()))
                    .andExpect(jsonPath("$.data.entries[0].action").value("PRODUCT_CREATED"))
                    .andExpect(jsonPath("$.data.entries[0].userId").value(userId.toString()))
                    .andExpect(jsonPath("$.data.hasMore").value(false));
        }

        @Test
        @DisplayName("→ 200 with empty array when no matching entries exist")
        void returns200WithEmptyList() throws Exception {
            UUID entityId = UUID.randomUUID();
            when(auditPort.findByEntityTypeAndEntityId(eq("Product"), any(UUID.class), eq(0), eq(20)))
                    .thenReturn(new AuditPort.AuditPage(List.of(), false));

            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "Product")
                            .param("entityId", entityId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)));
        }
    }

    // ══════════════════════════════════════════════════
    // GET /api/v1/audit — entityType only (no entityId)
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/audit?entityType=User (no entityId)")
    class GetAuditEntityTypeOnly {

        @Test
        @DisplayName("→ 200 with entries filtered by entityType only")
        void returns200WithEntityTypeFilteredList() throws Exception {
            UUID userId   = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();

            when(auditPort.findByEntityType("User", 0, 20))
                    .thenReturn(new AuditPort.AuditPage(List.of(new AuditPort.AuditEntryRecord(
                            UUID.randomUUID(), "User", entityId, "USER_REGISTERED",
                            null, "{}", userId, "+237690000001", Instant.now())), false));

            mockMvc.perform(get("/api/v1/audit")
                            .param("entityType", "User"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(1)))
                    .andExpect(jsonPath("$.data.entries[0].entityType").value("User"))
                    .andExpect(jsonPath("$.data.entries[0].action").value("USER_REGISTERED"));
        }
    }

    // ══════════════════════════════════════════════════
    // GET /api/v1/audit — no params (full tenant log)
    // ══════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/audit (no params)")
    class GetAuditNoParams {

        @Test
        @DisplayName("→ 200 with full tenant log (NOT 400)")
        void returns200WithFullTenantLog() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();

            when(auditPort.findAll(0, 20))
                    .thenReturn(new AuditPort.AuditPage(List.of(
                            new AuditPort.AuditEntryRecord(
                                    UUID.randomUUID(), "User", userId, "USER_REGISTERED",
                                    null, "{}", userId, "+237690000001", Instant.now()),
                            new AuditPort.AuditEntryRecord(
                                    UUID.randomUUID(), "Tenant", entityId, "ONBOARDING_COMPLETED",
                                    null, "{}", userId, "+237690000001", Instant.now())
                    ), false));

            mockMvc.perform(get("/api/v1/audit"))
                    .andExpect(status().isOk())  // MUST NOT be 400
                    .andExpect(jsonPath("$.data.entries", hasSize(2)));
        }

        @Test
        @DisplayName("→ 200 with empty list when no audit entries exist (full tenant log is empty)")
        void returns200WithEmptyFullLog() throws Exception {
            when(auditPort.findAll(0, 20)).thenReturn(new AuditPort.AuditPage(List.of(), false));

            mockMvc.perform(get("/api/v1/audit"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)));
        }
    }

    // ══════════════════════════════════════════════════
    // DELETE /api/v1/audit/{id} — immutability guard
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("DELETE /api/v1/audit/{id} → 403 AUDIT_IMMUTABLE")
    void delete_returns403AuditImmutable() throws Exception {
        mockMvc.perform(delete("/api/v1/audit/" + UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("AUDIT_IMMUTABLE"))
                .andExpect(jsonPath("$.error").value(containsString("journal")));
    }

    // ══════════════════════════════════════════════════
    // PUT /api/v1/audit/{id} — immutability guard
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("PUT /api/v1/audit/{id} → 403 AUDIT_IMMUTABLE")
    void put_returns403AuditImmutable() throws Exception {
        mockMvc.perform(put("/api/v1/audit/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("AUDIT_IMMUTABLE"));
    }

    // ══════════════════════════════════════════════════
    // Input validation — invalid UUID
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("GET /api/v1/audit?entityId=not-a-uuid → 422 VALIDATION_ERROR")
    void invalidEntityId_returns422() throws Exception {
        // RED: UUID.fromString("not-a-uuid") throws IllegalArgumentException → currently causes 500
        // GREEN: controller catches and throws DomainException(VALIDATION_ERROR) → 422
        mockMvc.perform(get("/api/v1/audit")
                        .param("entityType", "Product")
                        .param("entityId", "not-a-valid-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    // ══════════════════════════════════════════════════
    // JWT protection
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("GET /api/v1/audit without JWT → 401 Unauthorized")
    void getAudit_withoutJwt_returns401() throws Exception {
        // Reset filter to pass through WITHOUT setting SecurityContext authentication
        doAnswer(inv -> {
            SecurityContextHolder.clearContext(); // no authentication
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isUnauthorized());
    }

    // ══════════════════════════════════════════════════
    // Response structure validation
    // ══════════════════════════════════════════════════

    @Test
    @DisplayName("GET /api/v1/audit → response includes all AuditEntryResponse fields")
    void getAudit_responseIncludesAllFields() throws Exception {
        UUID id       = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID userId   = UUID.randomUUID();
        Instant now   = Instant.now();

        when(auditPort.findAll(0, 20))
                .thenReturn(new AuditPort.AuditPage(List.of(new AuditPort.AuditEntryRecord(
                        id, "Product", entityId, "PRODUCT_CREATED",
                        "{\"old\":\"value\"}", "{\"new\":\"value\"}", userId, "+237690000001", now)), false));

        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data.entries[0].entityType").value("Product"))
                .andExpect(jsonPath("$.data.entries[0].entityId").value(entityId.toString()))
                .andExpect(jsonPath("$.data.entries[0].action").value("PRODUCT_CREATED"))
                .andExpect(jsonPath("$.data.entries[0].valueBefore").value("{\"old\":\"value\"}"))
                .andExpect(jsonPath("$.data.entries[0].valueAfter").value("{\"new\":\"value\"}"))
                .andExpect(jsonPath("$.data.entries[0].userId").value(userId.toString()))
                .andExpect(jsonPath("$.data.entries[0].occurredAt").isNotEmpty());
    }
}
