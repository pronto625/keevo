package com.keevo.identity.employee.adapter.out.persistence;

import com.keevo.identity.employee.domain.port.out.EmployeeActivityRepository.RawActivity;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1s-14-6 — the FR67 activity SQL against a real PostgreSQL (the public baseline holds
 * {@code audit_log}, {@code sales} and {@code refresh_tokens}). The shared database may hold rows
 * of other tests, so every assertion is scoped to this test's random user ids.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("JdbcEmployeeActivityRepository on PostgreSQL")
class JdbcEmployeeActivityRepositoryPostgresIntegrationTest extends PostgresIntegrationTest {

    /** Wednesday 2026-10-14 12:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-10-14T11:00:00Z");
    private static final Instant TODAY_START = Instant.parse("2026-10-13T23:00:00Z");
    private static final Instant WEEK_START = Instant.parse("2026-10-11T23:00:00Z");
    private static final String TENANT = "kv_it_activity";

    @Autowired JdbcTemplate jdbc;

    private JdbcEmployeeActivityRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcEmployeeActivityRepository(jdbc);
    }

    private RawActivity fetch(UUID user) {
        Map<UUID, RawActivity> rows = repo.findRawActivity(List.of(user), TODAY_START, WEEK_START, NOW, TENANT);
        assertThat(rows).containsOnlyKeys(user);
        return rows.get(user);
    }

    @Test
    @DisplayName("today vs week: 2×5 000 today + 1×3 000 on Monday; CANCELLED and last week excluded")
    void todayVsWeek() {
        UUID user = UUID.randomUUID();
        sale(user, NOW.minus(Duration.ofHours(2)), 5_000, "COMPLETED");
        sale(user, NOW.minus(Duration.ofHours(1)), 5_000, "COMPLETED");
        sale(user, Instant.parse("2026-10-12T10:00:00Z"), 3_000, "COMPLETED");      // Monday
        sale(user, NOW.minus(Duration.ofMinutes(30)), 9_000, "CANCELLED");           // not counted
        sale(user, Instant.parse("2026-10-11T22:30:00Z"), 7_000, "COMPLETED");      // Sunday 23:30 WAT

        RawActivity r = fetch(user);
        assertThat(r.todayCount()).isEqualTo(2);
        assertThat(r.todayAmount()).isEqualTo(10_000);
        assertThat(r.weekCount()).isEqualTo(3);
        assertThat(r.weekAmount()).isEqualTo(13_000);
        assertThat(r.lastSaleAt()).isEqualTo(NOW.minus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("future-dated sale (device clock ahead) ignored for last sale")
    void futureSaleIgnoredForLastSale() {
        UUID user = UUID.randomUUID();
        Instant past = NOW.minus(Duration.ofDays(3));
        sale(user, past, 1_000, "COMPLETED");
        sale(user, NOW.plus(Duration.ofDays(2)), 2_000, "COMPLETED");

        RawActivity r = fetch(user);
        assertThat(r.lastSaleAt()).isEqualTo(past);
        assertThat(r.weekAmount()).as("future sale outside the window").isZero();
    }

    @Test
    @DisplayName("login = latest USER_AUTHENTICATED; last audit = latest action of any kind")
    void loginVsOtherAudit() {
        UUID user = UUID.randomUUID();
        Instant oldLogin = NOW.minus(Duration.ofDays(3));
        Instant login = NOW.minus(Duration.ofDays(1));
        Instant other = NOW.minus(Duration.ofHours(5));
        audit(user, "USER_AUTHENTICATED", oldLogin);
        audit(user, "USER_AUTHENTICATED", login);
        audit(user, "PRODUCT_CREATED", other);
        audit(UUID.randomUUID(), "USER_AUTHENTICATED", NOW.minus(Duration.ofMinutes(1)));   // someone else

        RawActivity r = fetch(user);
        assertThat(r.lastLoginAt()).isEqualTo(login);
        assertThat(r.lastAuditAt()).isEqualTo(other);
    }

    @Test
    @DisplayName("active session only for a non-revoked, unexpired token of this tenant")
    void activeSession() {
        UUID active = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        token(active, TENANT, false, NOW.plus(Duration.ofDays(10)));
        token(revoked, TENANT, true, NOW.plus(Duration.ofDays(10)));
        token(expired, TENANT, false, NOW.minus(Duration.ofDays(1)));
        token(otherTenant, "kv_other_tenant", false, NOW.plus(Duration.ofDays(10)));

        Map<UUID, RawActivity> rows = repo.findRawActivity(
                List.of(active, revoked, expired, otherTenant), TODAY_START, WEEK_START, NOW, TENANT);
        assertThat(rows.get(active).activeSession()).isTrue();
        assertThat(rows.get(revoked).activeSession()).isFalse();
        assertThat(rows.get(expired).activeSession()).isFalse();
        assertThat(rows.get(otherTenant).activeSession()).isFalse();
    }

    @Test
    @DisplayName("user without any activity → present with zeros and nulls")
    void noActivity() {
        UUID user = UUID.randomUUID();
        RawActivity r = fetch(user);
        assertThat(r.todayCount()).isZero();
        assertThat(r.weekAmount()).isZero();
        assertThat(r.lastLoginAt()).isNull();
        assertThat(r.lastAuditAt()).isNull();
        assertThat(r.lastSaleAt()).isNull();
        assertThat(r.activeSession()).isFalse();
    }

    @Test
    @DisplayName("empty id list → empty map, no query")
    void emptyIds() {
        assertThat(repo.findRawActivity(List.of(), TODAY_START, WEEK_START, NOW, TENANT)).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void sale(UUID sellerUserId, Instant occurredAt, int amount, String status) {
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', ?, ?, ?, 0)",
                UUID.randomUUID(), Timestamp.from(occurredAt), sellerUserId, Timestamp.from(occurredAt),
                status, UUID.randomUUID(), amount);
    }

    private void audit(UUID userId, String action, Instant occurredAt) {
        jdbc.update("INSERT INTO audit_log (id, action, entity_id, entity_type, occurred_at, user_id) "
                        + "VALUES (?, ?, ?, 'User', ?, ?)",
                UUID.randomUUID(), action, userId, Timestamp.from(occurredAt), userId);
    }

    private void token(UUID userId, String tenant, boolean revoked, Instant expiresAt) {
        jdbc.update("INSERT INTO public.refresh_tokens (id, created_at, updated_at, expires_at, revoked, "
                        + "tenant_id, token_hash, user_id) VALUES (?, now(), now(), ?, ?, ?, ?, ?)",
                UUID.randomUUID(), Timestamp.from(expiresAt), revoked, tenant,
                "hash-" + UUID.randomUUID(), userId);
    }
}
