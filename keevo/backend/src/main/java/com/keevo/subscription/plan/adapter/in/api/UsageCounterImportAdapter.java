package com.keevo.subscription.plan.adapter.in.api;

import com.keevo.subscription.api.UsageCounterImportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.5 — implements {@link UsageCounterImportPort}: one {@code INSERT … SELECT FROM unnest(…)} upsert on the
 * counters' primary key {@code (tenant_id, metric, period_key)}, keeping the higher value.
 */
@Component
class UsageCounterImportAdapter implements UsageCounterImportPort {

    static final String UPSERT_SQL = """
            INSERT INTO public.usage_counters AS c (tenant_id, metric, period, period_key, used, updated_at)
            SELECT ?, x.metric, x.period, x.period_key, x.used, now()
            FROM unnest(?::text[], ?::text[], ?::text[], ?::bigint[]) AS x(metric, period, period_key, used)
            ON CONFLICT (tenant_id, metric, period_key)
            DO UPDATE SET used = GREATEST(c.used, EXCLUDED.used), updated_at = now()
            WHERE c.used < EXCLUDED.used
            """;

    private final JdbcTemplate jdbc;

    UsageCounterImportAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int importCounters(UUID tenantId, List<ImportedCounter> counters) {
        if (counters == null || counters.isEmpty()) {
            return 0;
        }
        return jdbc.execute((java.sql.Connection c) -> {
            try (var ps = c.prepareStatement(UPSERT_SQL)) {
                Array metrics = c.createArrayOf("text", counters.stream().map(k -> k.metric().name()).toArray());
                Array periods = c.createArrayOf("text", counters.stream().map(k -> k.period().name()).toArray());
                Array keys = c.createArrayOf("text", counters.stream().map(ImportedCounter::periodKey).toArray());
                Array used = c.createArrayOf("bigint", counters.stream().map(ImportedCounter::used).toArray());
                ps.setObject(1, tenantId);
                ps.setArray(2, metrics);
                ps.setArray(3, periods);
                ps.setArray(4, keys);
                ps.setArray(5, used);
                return ps.executeUpdate();
            }
        });
    }
}
