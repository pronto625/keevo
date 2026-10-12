package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AuditPort     auditPort;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

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

            when(auditPort.search(eq(new AuditPort.AuditQuery(null, null, null, null, null, "Product", entityId)),
                    isNull(), eq(0), eq(20)))
                    .thenReturn(slice(List.of(new AuditPort.AuditEntryRecord(
                            UUID.randomUUID(), "Product", entityId, "PRODUCT_CREATED",
                            null, "{\"name\":\"test\"}", userId, "+237690000001", now))));

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
            when(auditPort.search(any(), isNull(), eq(0), eq(20))).thenReturn(slice(List.of()));

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

            when(auditPort.search(eq(new AuditPort.AuditQuery(null, null, null, null, null, "User", null)),
                    isNull(), eq(0), eq(20)))
                    .thenReturn(slice(List.of(new AuditPort.AuditEntryRecord(
                            UUID.randomUUID(), "User", entityId, "USER_REGISTERED",
                            null, "{}", userId, "+237690000001", Instant.now()))));

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

            when(auditPort.search(eq(AuditPort.AuditQuery.none()), isNull(), eq(0), eq(20)))
                    .thenReturn(slice(List.of(
                            new AuditPort.AuditEntryRecord(
                                    UUID.randomUUID(), "User", userId, "USER_REGISTERED",
                                    null, "{}", userId, "+237690000001", Instant.now()),
                            new AuditPort.AuditEntryRecord(
                                    UUID.randomUUID(), "Tenant", entityId, "ONBOARDING_COMPLETED",
                                    null, "{}", userId, "+237690000001", Instant.now())
                    )));

            mockMvc.perform(get("/api/v1/audit"))
                    .andExpect(status().isOk())  // MUST NOT be 400
                    .andExpect(jsonPath("$.data.entries", hasSize(2)));
        }

        @Test
        @DisplayName("→ 200 with empty list when no audit entries exist (full tenant log is empty)")
        void returns200WithEmptyFullLog() throws Exception {
            when(auditPort.search(any(), isNull(), eq(0), eq(20))).thenReturn(slice(List.of()));

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
    @DisplayName("GET /api/v1/audit?entityId=not-a-uuid → 400 VALIDATION_ERROR (v1s-18-6 contract)")
    void invalidEntityId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .param("entityType", "Product")
                        .param("entityId", "not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
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

        when(auditPort.search(any(), isNull(), eq(0), eq(20)))
                .thenReturn(slice(List.of(new AuditPort.AuditEntryRecord(
                        id, "Product", entityId, "PRODUCT_CREATED",
                        "{\"old\":\"value\"}", "{\"new\":\"value\"}", userId, "+237690000001", now))));

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

    // ══════════════════════════════════════════════════
    // Story v1s-18-6 — OWNER-only, filters, cursor, detail
    // ══════════════════════════════════════════════════

    private static AuditPort.AuditSlice slice(List<AuditPort.AuditEntryRecord> entries) {
        return new AuditPort.AuditSlice(entries, false, null);
    }

    private void actAs(String role) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private static AuditPort.AuditEntryRecord enriched(UUID id, Instant at) {
        return new AuditPort.AuditEntryRecord(
                id, "Category", UUID.randomUUID(), "CATEGORY_CREATED",
                null, "{\"name\":\"Pagnes\"}", UUID.randomUUID(), "+237690000001", at,
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Boutique Centre", "Pagnes",
                "device-D", "SYNC", "{\"actorLabel\":\"Awa\"}", "Awa Diallo");
    }

    @Nested
    @DisplayName("v1s-18-6")
    class Enriched {

        @Test
        @DisplayName("EMPLOYEE → 403 on list")
        void employee_list_forbidden() throws Exception {
            actAs("EMPLOYEE");
            mockMvc.perform(get("/api/v1/audit"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
            verify(auditPort, never()).search(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("EMPLOYEE → 403 on detail")
        void employee_detail_forbidden() throws Exception {
            actAs("EMPLOYEE");
            mockMvc.perform(get("/api/v1/audit/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden());
            verify(auditPort, never()).findById(any());
        }

        @Test
        @DisplayName("all filters are bound into the AuditQuery; nextCursor and enriched fields are returned")
        void filters_bound_and_enrichedFields() throws Exception {
            UUID storeId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();
            Instant from = Instant.parse("2026-10-01T00:00:00Z");
            Instant to = Instant.parse("2026-10-12T23:59:59Z");
            var expected = new AuditPort.AuditQuery(from, to, storeId, userId, "CATEGORY_CREATED", "Category", entityId);
            when(auditPort.search(eq(expected), isNull(), eq(0), eq(50)))
                    .thenReturn(new AuditPort.AuditSlice(
                            List.of(enriched(UUID.randomUUID(), Instant.now())), true, "next-cursor"));

            mockMvc.perform(get("/api/v1/audit")
                            .param("from", from.toString()).param("to", to.toString())
                            .param("storeId", storeId.toString()).param("userId", userId.toString())
                            .param("action", "CATEGORY_CREATED").param("entityType", "Category")
                            .param("entityId", entityId.toString()).param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasMore").value(true))
                    .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                    .andExpect(jsonPath("$.data.size").value(50))
                    .andExpect(jsonPath("$.data.entries[0].storeName").value("Boutique Centre"))
                    .andExpect(jsonPath("$.data.entries[0].entityLabel").value("Pagnes"))
                    .andExpect(jsonPath("$.data.entries[0].source").value("SYNC"))
                    .andExpect(jsonPath("$.data.entries[0].deviceId").value("device-D"))
                    .andExpect(jsonPath("$.data.entries[0].actorName").value("Awa Diallo"))
                    .andExpect(jsonPath("$.data.entries[0].metadata.actorLabel").value("Awa"));
        }

        @Test
        @DisplayName("cursor round-trip: a valid cursor is passed through to search()")
        void cursor_roundTrip() throws Exception {
            String cursor = new AuditPort.AuditCursor(Instant.parse("2026-10-12T08:15:30.123456Z"),
                    UUID.randomUUID()).encode();
            when(auditPort.search(eq(AuditPort.AuditQuery.none()), eq(cursor), eq(0), eq(20)))
                    .thenReturn(slice(List.of()));

            mockMvc.perform(get("/api/v1/audit").param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
            verify(auditPort).search(eq(AuditPort.AuditQuery.none()), eq(cursor), eq(0), eq(20));
        }

        @Test
        @DisplayName("bad params → 400 VALIDATION_ERROR (cursor, storeId, userId, from>to, size bounds, from format)")
        void badParams_400() throws Exception {
            String[][] cases = {
                    {"cursor", "%%%not-base64"},
                    {"cursor", "bm90LWEtY3Vyc29y"},  // base64 of "not-a-cursor"
                    {"storeId", "nope"},
                    {"userId", "nope"},
                    {"size", "0"},
                    {"size", "101"},
                    {"from", "yesterday"},
            };
            for (String[] c : cases) {
                mockMvc.perform(get("/api/v1/audit").param(c[0], c[1]))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
            }
            mockMvc.perform(get("/api/v1/audit")
                            .param("from", "2026-10-12T00:00:00Z").param("to", "2026-10-01T00:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
            verify(auditPort, never()).search(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("v1s-18-7 — q is trimmed and bound into the AuditQuery")
        void q_bound() throws Exception {
            var expected = new AuditPort.AuditQuery(null, null, null, null, null, "Product", null, "pagne");
            when(auditPort.search(eq(expected), isNull(), eq(0), eq(20))).thenReturn(slice(List.of()));

            mockMvc.perform(get("/api/v1/audit").param("entityType", "Product").param("q", "  pagne "))
                    .andExpect(status().isOk());
            verify(auditPort).search(eq(expected), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("v1s-18-7 — blank q is ignored")
        void q_blank_ignored() throws Exception {
            when(auditPort.search(eq(AuditPort.AuditQuery.none()), isNull(), eq(0), eq(20)))
                    .thenReturn(slice(List.of()));

            mockMvc.perform(get("/api/v1/audit").param("q", "   "))
                    .andExpect(status().isOk());
            verify(auditPort).search(eq(AuditPort.AuditQuery.none()), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("v1s-18-7 — q longer than 100 chars → 400 VALIDATION_ERROR")
        void q_tooLong_400() throws Exception {
            mockMvc.perform(get("/api/v1/audit").param("q", "x".repeat(101)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
            verify(auditPort, never()).search(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("page * size beyond the int range → 400 VALIDATION_ERROR (no negative offset / 500)")
        void pageOffsetOverflow_400() throws Exception {
            mockMvc.perform(get("/api/v1/audit").param("page", "50000000").param("size", "100"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
            verify(auditPort, never()).search(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("legacy page/size paging still works and echoes page/size")
        void legacyPaging() throws Exception {
            when(auditPort.search(eq(AuditPort.AuditQuery.none()), isNull(), eq(2), eq(10)))
                    .thenReturn(new AuditPort.AuditSlice(List.of(), true, "c"));

            mockMvc.perform(get("/api/v1/audit").param("page", "2").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(2))
                    .andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.hasMore").value(true));
        }

        @Test
        @DisplayName("GET /{id} → full entry with metadata as a JSON object")
        void detail_ok() throws Exception {
            UUID id = UUID.randomUUID();
            when(auditPort.findById(id)).thenReturn(java.util.Optional.of(enriched(id, Instant.now())));

            mockMvc.perform(get("/api/v1/audit/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.storeId").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.data.storeName").value("Boutique Centre"))
                    .andExpect(jsonPath("$.data.entityLabel").value("Pagnes"))
                    .andExpect(jsonPath("$.data.deviceId").value("device-D"))
                    .andExpect(jsonPath("$.data.source").value("SYNC"))
                    .andExpect(jsonPath("$.data.metadata.actorLabel").value("Awa"))
                    .andExpect(jsonPath("$.data.actorName").value("Awa Diallo"));
        }

        @Test
        @DisplayName("GET /{id} on a legacy row → enrichment fields null, actorName still present")
        void detail_legacyRow() throws Exception {
            UUID id = UUID.randomUUID();
            var legacy = new AuditPort.AuditEntryRecord(id, "Product", UUID.randomUUID(), "PRODUCT_CREATED",
                    null, "{}", UUID.randomUUID(), null, Instant.now(),
                    null, null, null, null, null, null, "Le propriétaire");
            when(auditPort.findById(id)).thenReturn(java.util.Optional.of(legacy));

            mockMvc.perform(get("/api/v1/audit/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.source").value(nullValue()))
                    .andExpect(jsonPath("$.data.metadata").value(nullValue()))
                    .andExpect(jsonPath("$.data.storeName").value(nullValue()))
                    .andExpect(jsonPath("$.data.actorName").value("Le propriétaire"));
        }

        @Test
        @DisplayName("GET /{id} unknown → 404 AUDIT_ENTRY_NOT_FOUND")
        void detail_404() throws Exception {
            UUID id = UUID.randomUUID();
            when(auditPort.findById(id)).thenReturn(java.util.Optional.empty());

            mockMvc.perform(get("/api/v1/audit/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.domainCode").value("AUDIT_ENTRY_NOT_FOUND"));
        }
    }
}
