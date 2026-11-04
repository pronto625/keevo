package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    static final String CONSUME_CREDITS_SQL = """
            UPDATE public.usage_credits SET remaining = remaining - ?, updated_at = now()
            WHERE tenant_id = ? AND metric = ? AND remaining >= ?
            RETURNING remaining
            """;

    static final String ADD_CREDITS_SQL = """
            INSERT INTO public.usage_credits AS c (tenant_id, metric, remaining, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (tenant_id, metric) DO UPDATE SET remaining = c.remaining + EXCLUDED.remaining,
                updated_at = now()
            RETURNING c.remaining
            """;

    static final String USAGE_SQL = """
            SELECT metric, period_key, used, NULL::bigint AS remaining
            FROM public.usage_counters WHERE tenant_id = ? AND period_key = ANY (?)
            UNION ALL
            SELECT metric, NULL, NULL, remaining FROM public.usage_credits WHERE tenant_id = ?
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
    public boolean consumeCredits(UUID tenantId, UsageMetric metric, int n) {
        return !jdbc.queryForList(CONSUME_CREDITS_SQL, Long.class, (long) n, tenantId, metric.name(), (long) n)
                .isEmpty();
    }

    @Override
    public long addCredits(UUID tenantId, UsageMetric metric, int n) {
        return jdbc.queryForObject(ADD_CREDITS_SQL, Long.class, tenantId, metric.name(), (long) n);
    }

    @Override
    public Usage usage(UUID tenantId, Collection<String> periodKeys) {
        Map<UsageMetric, Map<String, Long>> counters = new EnumMap<>(UsageMetric.class);
        Map<UsageMetric, Long> credits = new EnumMap<>(UsageMetric.class);
        jdbc.query(con -> {
            var ps = con.prepareStatement(USAGE_SQL);
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("varchar", periodKeys.toArray()));
            ps.setObject(3, tenantId);
            return ps;
        }, (ResultSet rs) -> read(rs, counters, credits));
        return new Usage(counters, credits);
    }

    private static void read(ResultSet rs, Map<UsageMetric, Map<String, Long>> counters,
                             Map<UsageMetric, Long> credits) throws SQLException {
        UsageMetric metric = UsageMetric.valueOf(rs.getString(1));
        String key = rs.getString(2);
        if (key != null) {
            counters.computeIfAbsent(metric, m -> new HashMap<>()).put(key, rs.getLong(3));
        } else {
            credits.put(metric, rs.getLong(4));
        }
    }

    @Override
    public int purge(String dayCutoff, String monthCutoff) {
        return jdbc.update(PURGE_SQL, QuotaPeriod.DAY.name(), dayCutoff)
                + jdbc.update(PURGE_SQL, QuotaPeriod.MONTH.name(), monthCutoff);
    }
}
