package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageCredits;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsagePackSource;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Story 19.8 — {@link UsageCounterStore} on {@code public.plan_quotas}, {@code public.usage_counters} and
 * {@code public.usage_credits}. Plain JDBC on schema-qualified tables: joins the caller's transaction whatever the
 * current tenant context. Every lookup is served by a primary key (or its prefix).
 *
 * <p>The increment is a guarded atomic upsert: {@code INSERT … SELECT … WHERE n <= limit ON CONFLICT DO UPDATE …
 * WHERE used + n <= limit RETURNING used}. PostgreSQL re-evaluates the {@code WHERE} of the conflict branch on the
 * latest row version under its row lock, so concurrent increments serialise and never overshoot — no read-check-write,
 * no lock held across calls.
 */
@Component
public class JdbcUsageCounterStore implements UsageCounterStore {

    private static final String QUOTAS_SQL = """
            SELECT t.id, t.plan_type, q.metric, q.period, q.quota_limit
            FROM public.tenants t
            LEFT JOIN public.plan_quotas q ON q.plan_type = t.plan_type %s
            WHERE %s
            """;
    private static final String METRIC_FILTER = "AND q.metric = ?";

    static final String PLAN_QUOTAS_SQL = "SELECT metric, period, quota_limit FROM public.plan_quotas "
            + "WHERE plan_type = ? %s";
    private static final String PLAN_METRIC_FILTER = "AND metric = ?";

    static final String INCREMENT_GUARDED_SQL = """
            INSERT INTO public.usage_counters AS c (tenant_id, metric, period, period_key, used, updated_at)
            SELECT ?, ?, ?, ?, ?, now() WHERE ? <= ?
            ON CONFLICT (tenant_id, metric, period_key)
            DO UPDATE SET used = c.used + EXCLUDED.used, updated_at = now()
            WHERE c.used + EXCLUDED.used <= ?
            RETURNING c.used
            """;

    static final String INCREMENT_UNLIMITED_SQL = """
            INSERT INTO public.usage_counters AS c (tenant_id, metric, period, period_key, used, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (tenant_id, metric, period_key)
            DO UPDATE SET used = c.used + EXCLUDED.used, updated_at = now()
            RETURNING c.used
            """;

    static final String USED_SQL =
            "SELECT used FROM public.usage_counters WHERE tenant_id = ? AND metric = ? AND period_key = ?";

    /**
     * Story 21.3 — the guarded pack debit and its journal row in ONE statement: the movement exists exactly when the
     * units were taken (a rolled-back probe leaves neither).
     */
    static final String CONSUME_CREDITS_SQL = """
            WITH c AS (
                UPDATE public.usage_credits SET remaining = remaining - ?, updated_at = now()
                WHERE tenant_id = ? AND metric = ? AND remaining >= ?
                RETURNING tenant_id, metric, remaining
            ), logged AS (
                INSERT INTO public.usage_credit_movements
                    (id, tenant_id, metric, period, delta, remaining_after, source, actor_id)
                SELECT ?, c.tenant_id, c.metric, ?, ?, c.remaining, 'CONSUMPTION', ? FROM c
            )
            SELECT remaining FROM c
            """;

    /** The pack credit (upsert) and its journal row in ONE statement. */
    static final String ADD_CREDITS_SQL = """
            WITH c AS (
                INSERT INTO public.usage_credits AS u (tenant_id, metric, remaining, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (tenant_id, metric) DO UPDATE SET remaining = u.remaining + EXCLUDED.remaining,
                    updated_at = now()
                RETURNING u.tenant_id, u.metric, u.remaining
            ), logged AS (
                INSERT INTO public.usage_credit_movements
                    (id, tenant_id, metric, period, delta, remaining_after, source, payment_request_id, actor_id)
                SELECT ?, c.tenant_id, c.metric, NULL, ?, c.remaining, ?, ?, ? FROM c
            )
            SELECT remaining FROM c
            """;

    /** Counters, packs and (Story 21.3) the pack units consumed since an instant — one query, three index scans. */
    static final String USAGE_SQL = """
            SELECT metric, 'C' AS kind, period_key, used AS amount
            FROM public.usage_counters WHERE tenant_id = ? AND period_key = ANY (?)
            UNION ALL
            SELECT metric, 'P', NULL, remaining FROM public.usage_credits WHERE tenant_id = ?
            UNION ALL
            SELECT metric, 'M', NULL, -sum(delta) FROM public.usage_credit_movements
            WHERE tenant_id = ? AND source = 'CONSUMPTION' AND created_at >= ? GROUP BY metric
            """;

    /**
     * Story 21.3 — the pack journal, newest first (keyset); the actor's name is the store member's (the company's
     * {@code employees}, by the {@code user_id} index) or else the platform user's (primary key) — joined per page row,
     * no N+1.
     */
    static final String PACK_MOVEMENTS_SQL = """
            SELECT m.id, m.metric, m.period, m.delta, m.remaining_after, m.source, m.actor_id,
                   COALESCE(NULLIF(btrim(concat_ws(' ', em.first_name, em.last_name)), ''),
                            NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '')) AS actor_name,
                   m.created_at
            FROM public.usage_credit_movements m
            LEFT JOIN LATERAL (SELECT x.first_name, x.last_name FROM %1$s.employees x WHERE x.user_id = m.actor_id
                               ORDER BY x.created_at LIMIT 1) em ON true
            LEFT JOIN public.users u ON u.id = m.actor_id
            WHERE m.tenant_id = (SELECT t.id FROM public.tenants t WHERE t.schema_name = ?) %2$s %3$s
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT ?
            """;

    /** ISO keys of one period kind compare as text; served by {@code ix_usage_counters_period_key}. */
    static final String PURGE_SQL = "DELETE FROM public.usage_counters WHERE period = ? AND period_key < ?";

    private final JdbcTemplate jdbc;

    public JdbcUsageCounterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantQuotas> quotasById(UUID tenantId, UsageMetric metric) {
        return quotas("t.id = ?", tenantId, metric);
    }

    @Override
    public Optional<TenantQuotas> quotasBySchema(String tenantSchema, UsageMetric metric) {
        return quotas("t.schema_name = ?", tenantSchema, metric);
    }

    @Override
    public List<PlanQuota> quotasOfPlan(String planCode, UsageMetric metric) {
        String sql = PLAN_QUOTAS_SQL.formatted(metric != null ? PLAN_METRIC_FILTER : "");
        Object[] args = metric != null ? new Object[] {planCode, metric.name()} : new Object[] {planCode};
        return jdbc.query(sql, (rs, i) -> new PlanQuota(UsageMetric.valueOf(rs.getString(1)),
                QuotaPeriod.valueOf(rs.getString(2)), rs.getInt(3)), args);
    }

    private Optional<TenantQuotas> quotas(String where, Object tenant, UsageMetric metric) {
        String sql = QUOTAS_SQL.formatted(metric != null ? METRIC_FILTER : "", where);
        Object[] args = metric != null ? new Object[] {metric.name(), tenant} : new Object[] {tenant};
        UUID[] id = new UUID[1];
        String[] plan = new String[1];
        List<PlanQuota> quotas = new ArrayList<>();
        jdbc.query(sql, (ResultSet rs) -> {
            id[0] = rs.getObject(1, UUID.class);
            plan[0] = rs.getString(2);
            String m = rs.getString(3);
            if (m != null) {
                quotas.add(new PlanQuota(UsageMetric.valueOf(m), QuotaPeriod.valueOf(rs.getString(4)), rs.getInt(5)));
            }
        }, args);
        return id[0] == null ? Optional.empty() : Optional.of(new TenantQuotas(id[0], plan[0], quotas));
    }

    @Override
    public OptionalLong increment(UUID tenantId, UsageMetric metric, QuotaPeriod period, String periodKey, int n,
                                  Integer limit) {
        List<Long> result = limit == null
                ? jdbc.queryForList(INCREMENT_UNLIMITED_SQL, Long.class, tenantId, metric.name(), period.name(),
                        periodKey, (long) n)
                : jdbc.queryForList(INCREMENT_GUARDED_SQL, Long.class, tenantId, metric.name(), period.name(),
                        periodKey, (long) n, (long) n, (long) limit, (long) limit);
        return result.isEmpty() ? OptionalLong.empty() : OptionalLong.of(result.get(0));
    }

    @Override
    public long used(UUID tenantId, UsageMetric metric, String periodKey) {
        List<Long> rows = jdbc.queryForList(USED_SQL, Long.class, tenantId, metric.name(), periodKey);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Override
    public boolean consumeCredits(UUID tenantId, UsageMetric metric, int n, QuotaPeriod period, UUID actorId) {
        return !jdbc.query(CONSUME_CREDITS_SQL, ps -> {
            ps.setLong(1, n);
            ps.setObject(2, tenantId, Types.OTHER);
            ps.setString(3, metric.name());
            ps.setLong(4, n);
            ps.setObject(5, UUID.randomUUID(), Types.OTHER);
            ps.setString(6, period == null ? null : period.name());
            ps.setInt(7, -n);
            ps.setObject(8, actorId, Types.OTHER);
        }, (rs, i) -> rs.getLong(1)).isEmpty();
    }

    @Override
    public long addCredits(UUID tenantId, UsageMetric metric, int n, UsageCredits.Grant grant) {
        List<Long> remaining = jdbc.query(ADD_CREDITS_SQL, ps -> {
            ps.setObject(1, tenantId, Types.OTHER);
            ps.setString(2, metric.name());
            ps.setLong(3, n);
            ps.setObject(4, UUID.randomUUID(), Types.OTHER);
            ps.setInt(5, n);
            ps.setString(6, grant.source().name());
            ps.setObject(7, grant.paymentRequestId(), Types.OTHER);
            ps.setObject(8, grant.actorId(), Types.OTHER);
        }, (rs, i) -> rs.getLong(1));
        return remaining.get(0);
    }

    @Override
    public Usage usage(UUID tenantId, Collection<String> periodKeys, Instant consumedSince) {
        Map<UsageMetric, Map<String, Long>> counters = new EnumMap<>(UsageMetric.class);
        Map<UsageMetric, Long> credits = new EnumMap<>(UsageMetric.class);
        Map<UsageMetric, Long> consumed = new EnumMap<>(UsageMetric.class);
        jdbc.query(con -> {
            var ps = con.prepareStatement(USAGE_SQL);
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("varchar", periodKeys.toArray()));
            ps.setObject(3, tenantId);
            ps.setObject(4, tenantId);
            ps.setTimestamp(5, Timestamp.from(consumedSince));
            return ps;
        }, (ResultSet rs) -> read(rs, counters, credits, consumed));
        return new Usage(counters, credits, consumed);
    }

    private static void read(ResultSet rs, Map<UsageMetric, Map<String, Long>> counters,
                             Map<UsageMetric, Long> credits, Map<UsageMetric, Long> consumed) throws SQLException {
        UsageMetric metric = UsageMetric.valueOf(rs.getString(1));
        switch (rs.getString(2)) {
            case "C" -> counters.computeIfAbsent(metric, m -> new HashMap<>()).put(rs.getString(3), rs.getLong(4));
            case "P" -> credits.put(metric, rs.getLong(4));
            default -> consumed.put(metric, rs.getLong(4));
        }
    }

    @Override
    public List<PackMovementRow> packMovements(String tenantSchema, UsageMetric metric, Instant beforeAt,
                                               UUID beforeId, int limit) {
        boolean keyset = beforeAt != null && beforeId != null;
        String schema = TenantSchema.validate(tenantSchema);
        String sql = PACK_MOVEMENTS_SQL.formatted("\"" + schema + "\"", metric != null ? "AND m.metric = ?" : "",
                keyset ? "AND (m.created_at, m.id) < (?, ?)" : "");
        return jdbc.query(sql, ps -> {
            int i = 1;
            ps.setString(i++, schema);
            if (metric != null) {
                ps.setString(i++, metric.name());
            }
            if (keyset) {
                ps.setTimestamp(i++, Timestamp.from(beforeAt));
                ps.setObject(i++, beforeId, Types.OTHER);
            }
            ps.setInt(i, limit);
        }, (rs, row) -> {
            String period = rs.getString("period");
            return new PackMovementRow(rs.getObject("id", UUID.class), UsageMetric.valueOf(rs.getString("metric")),
                    period == null ? null : QuotaPeriod.valueOf(period), rs.getInt("delta"),
                    rs.getLong("remaining_after"), UsagePackSource.valueOf(rs.getString("source")),
                    rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                    rs.getTimestamp("created_at").toInstant());
        });
    }

    @Override
    public int purge(String dayCutoff, String monthCutoff) {
        return jdbc.update(PURGE_SQL, QuotaPeriod.DAY.name(), dayCutoff)
                + jdbc.update(PURGE_SQL, QuotaPeriod.MONTH.name(), monthCutoff);
    }
}
