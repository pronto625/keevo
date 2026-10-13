package com.keevo.shared.infrastructure.activity;

import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9-4 — {@link UserActivityRecorder#INSERT_SQL} and {@link UserActivityPurgeJob#PURGE_SQL} on the
 * Flyway-migrated public schema (V18): tenant id resolved from the schema name, one row per key, purge.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("UserActivityRecorder / purge on PostgreSQL (9-4)")
class UserActivityRecorderPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final UUID tenantId = UUID.randomUUID();
    private final String schema = "kv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    private final UUID user = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM public.user_daily_activity WHERE user_id = ?", user);
        jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("insert resolves the tenant id from its schema; a duplicate key is ignored (multi-instance)")
    void insert_resolvesTenantAndDedupes() {
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')",
                tenantId, "KV-" + schema.substring(3).toUpperCase(), schema);
        // Two recorders = two instances: the in-memory dedupe does not span them, ON CONFLICT does.
        new UserActivityRecorder(jdbc, Clock.systemUTC(), Runnable::run).record(user, schema, "OWNER");
        new UserActivityRecorder(jdbc, Clock.systemUTC(), Runnable::run).record(user, schema, "OWNER");

        assertThat(jdbc.queryForList("SELECT tenant_id, activity_date FROM public.user_daily_activity WHERE user_id = ?",
                user)).singleElement().satisfies(row -> {
                    assertThat(row.get("tenant_id")).isEqualTo(tenantId);
                    assertThat(((Date) row.get("activity_date")).toLocalDate())
                            .isEqualTo(LocalDate.now(ZoneId.of("Africa/Douala")));
                });
    }

    @Test
    @DisplayName("unknown schema: nothing inserted, no error")
    void insert_unknownSchema() {
        new UserActivityRecorder(jdbc, Clock.systemUTC(), Runnable::run).record(user, "kv_zz9zz9", "OWNER");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.user_daily_activity WHERE user_id = ?",
                Long.class, user)).isZero();
    }

    @Test
    @DisplayName("purge deletes rows older than 760 days only")
    void purge() {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Douala"));
        String insert = "INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) VALUES (?, ?, ?)";
        jdbc.update(insert, user, tenantId, Date.valueOf(today.minusDays(761)));
        jdbc.update(insert, user, tenantId, Date.valueOf(today.minusDays(760)));
        jdbc.update(insert, user, tenantId, Date.valueOf(today));

        new UserActivityPurgeJob(jdbc, Clock.systemUTC()).purge();

        assertThat(jdbc.queryForList("SELECT activity_date FROM public.user_daily_activity WHERE user_id = ? "
                + "ORDER BY activity_date", Date.class, user))
                .extracting(Date::toLocalDate)
                .containsExactly(today.minusDays(760), today);
    }
}
