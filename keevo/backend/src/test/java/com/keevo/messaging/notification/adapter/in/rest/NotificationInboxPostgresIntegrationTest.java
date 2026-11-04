package com.keevo.messaging.notification.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 19.9 — the notification inbox end to end on PostgreSQL: a business notification sent through the primary
 * {@link PushNotifier} lands as an unread row of each owner (and the push is still delivered by the delivery
 * channel); list (own rows, newest first, paginated), unread count, idempotent mark-read, another user's row answered
 * 404, mark-all-read, page/size validation.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Notification inbox — full context on PostgreSQL (Story 19.9)")
class NotificationInboxPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PushNotifier pushNotifier;

    private String schema;
    private UUID ownerId;
    private UUID employeeId;
    private String ownerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        ownerId = user("OWNER");
        employeeId = user("EMPLOYEE");
        ownerToken = TestAccess.token(jdbc, jwtTokenProvider, ownerId, schema, "OWNER");
        // an employee needs its active employee row (runtime guards of the access filter)
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) SELECT ?, id, 'Awa', 'Ngo', false FROM \"" + schema + "\".stores LIMIT 1", employeeId);
        employeeToken = TestAccess.token(jdbc, jwtTokenProvider, employeeId, schema, "EMPLOYEE");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        String phone = "+2376" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, ?)", id, phone, role);
        return id;
    }

    private UUID row(UUID userId, String title, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".notifications (id, user_id, type, title, body, created_at) "
                + "VALUES (?, ?, 'TEST', ?, 'corps', ?)", id, userId, title, Timestamp.from(createdAt));
        return id;
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String body = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private long unread(String token) throws Exception {
        return getJson("/api/v1/notifications/unread-count", token).path("data").path("count").asLong();
    }

    @Test
    @DisplayName("a business notification (notifyOwners) → one unread row for the owner, none for the employee")
    void businessNotificationPersisted() throws Exception {
        long before = unread(ownerToken);

        pushNotifier.notifyOwners(schema, NotificationPayload.of("STOCK_ALERT", "Stock bas", "Riz : 2 restants",
                "/stock/42", Map.of("productId", "42")));

        assertThat(unread(ownerToken)).isEqualTo(before + 1);
        JsonNode first = getJson("/api/v1/notifications?page=0&size=20", ownerToken).path("data").get(0);
        assertThat(first.path("type").asText()).isEqualTo("STOCK_ALERT");
        assertThat(first.path("title").asText()).isEqualTo("Stock bas");
        assertThat(first.path("body").asText()).isEqualTo("Riz : 2 restants");
        assertThat(first.path("data").path("deepLink").asText()).isEqualTo("/stock/42");
        assertThat(first.path("data").path("productId").asText()).isEqualTo("42");
        assertThat(first.path("read").asBoolean()).isFalse();
        assertThat(unread(employeeToken)).isZero();
        assertThat(getJson("/api/v1/notifications", employeeToken).path("data")).isEmpty();
    }

    @Test
    @DisplayName("list: own rows only, newest first (created_at DESC, id DESC), paginated envelope")
    void listPaginated() throws Exception {
        Instant t = Instant.parse("2026-11-04T09:00:00Z");
        row(ownerId, "ancienne", t);
        row(ownerId, "milieu", t.plusSeconds(60));
        row(ownerId, "récente", t.plusSeconds(120));
        row(employeeId, "autre", t.plusSeconds(180));

        JsonNode page0 = getJson("/api/v1/notifications?page=0&size=2", ownerToken);
        assertThat(page0.path("data")).extracting(n -> n.path("title").asText()).containsExactly("récente", "milieu");
        assertThat(page0.path("meta").path("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.path("meta").path("totalPages").asInt()).isEqualTo(2);
        JsonNode page1 = getJson("/api/v1/notifications?page=1&size=2", ownerToken);
        assertThat(page1.path("data")).extracting(n -> n.path("title").asText()).containsExactly("ancienne");
    }

    @Test
    @DisplayName("mark read: own row 204 then 204 again (read_at set once); another user's or unknown id → 404")
    void markRead() throws Exception {
        UUID own = row(ownerId, "à lire", Instant.now());
        UUID foreign = row(employeeId, "pas à moi", Instant.now());

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", own).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        Timestamp readAt = jdbc.queryForObject("SELECT read_at FROM \"" + schema + "\".notifications WHERE id = ?",
                Timestamp.class, own);
        assertThat(readAt).isNotNull();
        mockMvc.perform(patch("/api/v1/notifications/{id}/read", own).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at FROM \"" + schema + "\".notifications WHERE id = ?",
                Timestamp.class, own)).isEqualTo(readAt);

        for (UUID id : new UUID[]{foreign, UUID.randomUUID()}) {
            mockMvc.perform(patch("/api/v1/notifications/{id}/read", id)
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.domainCode").value("NOTIFICATION_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT read_at FROM \"" + schema + "\".notifications WHERE id = ?",
                Timestamp.class, foreign)).isNull();
    }

    @Test
    @DisplayName("mark all read: every own unread row read, the other user's untouched")
    void markAllRead() throws Exception {
        row(ownerId, "a", Instant.now());
        row(ownerId, "b", Instant.now());
        row(employeeId, "c", Instant.now());

        mockMvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertThat(unread(ownerToken)).isZero();
        assertThat(unread(employeeToken)).isEqualTo(1);
    }

    @Test
    @DisplayName("page < 0 or size outside 1–100 → 400")
    void badPaging() throws Exception {
        for (String query : new String[]{"page=-1", "size=0", "size=101"}) {
            mockMvc.perform(get("/api/v1/notifications?" + query).header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isBadRequest());
        }
    }
}
