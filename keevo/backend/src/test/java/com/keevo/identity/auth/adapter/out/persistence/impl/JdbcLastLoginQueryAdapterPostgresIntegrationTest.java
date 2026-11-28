package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P02 — the last sign-in SQL against a real PostgreSQL (the public baseline holds {@code audit_log}). The shared
 * database may hold rows of other tests, so every assertion uses this test's random user ids.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("JdbcLastLoginQueryAdapter on PostgreSQL")
class JdbcLastLoginQueryAdapterPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private JdbcLastLoginQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JdbcLastLoginQueryAdapter(jdbc);
    }

    @Test
    @DisplayName("latest of two sign-ins; a later other action and another user's sign-in are ignored")
    void latestSignIn() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant first = Instant.parse("2026-11-20T08:00:00Z");
        Instant latest = Instant.parse("2026-11-27T10:15:00Z");
        audit(user, "USER_AUTHENTICATED", first);
        audit(user, "USER_AUTHENTICATED", latest);
        audit(user, "SALE_CREATED", Instant.parse("2026-11-28T09:00:00Z"));
        audit(other, "USER_AUTHENTICATED", Instant.parse("2026-11-28T11:00:00Z"));

        assertThat(adapter.lastLoginAt(user)).contains(latest);
    }

    @Test
    @DisplayName("no sign-in → empty")
    void noSignIn() {
        UUID user = UUID.randomUUID();
        audit(user, "SALE_CREATED", Instant.parse("2026-11-28T09:00:00Z"));

        assertThat(adapter.lastLoginAt(user)).isEmpty();
        assertThat(adapter.lastLoginAt(UUID.randomUUID())).isEmpty();
    }

    private void audit(UUID userId, String action, Instant occurredAt) {
        jdbc.update("INSERT INTO audit_log (id, action, entity_id, entity_type, occurred_at, user_id) "
                        + "VALUES (?, ?, ?, 'User', ?, ?)",
                UUID.randomUUID(), action, userId, Timestamp.from(occurredAt), userId);
    }
}
