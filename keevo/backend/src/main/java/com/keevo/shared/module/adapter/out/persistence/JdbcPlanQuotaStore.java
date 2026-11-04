package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.module.application.port.PlanQuotaStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 19.8 — {@link PlanQuotaStore} on {@code public.plan_quotas}: one query for the quotas of several plans
 * (primary-key prefix), a replace as one DELETE plus one batch INSERT, on the caller's transaction.
 */
@Component
public class JdbcPlanQuotaStore implements PlanQuotaStore {

    static final String FIND_SQL = "SELECT plan_type, metric, period, quota_limit FROM public.plan_quotas "
            + "WHERE plan_type = ANY (?) ORDER BY plan_type, metric, period";
    static final String DELETE_SQL = "DELETE FROM public.plan_quotas WHERE plan_type = ?";
    static final String INSERT_SQL = "INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit, "
            + "updated_at) VALUES (?, ?, ?, ?, now())";

    private final JdbcTemplate jdbc;

    public JdbcPlanQuotaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<PlanType, List<PlanQuota>> quotas(Collection<PlanType> planTypes) {
        Map<PlanType, List<PlanQuota>> result = new LinkedHashMap<>();
        if (planTypes.isEmpty()) {
            return result;
        }
        String[] codes = planTypes.stream().map(PlanType::code).distinct().toArray(String[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement(FIND_SQL);
            ps.setArray(1, con.createArrayOf("varchar", codes));
            return ps;
        }, (ResultSet rs) -> {
            result.computeIfAbsent(PlanType.of(rs.getString(1)), p -> new ArrayList<>())
                    .add(new PlanQuota(UsageMetric.valueOf(rs.getString(2)), QuotaPeriod.valueOf(rs.getString(3)),
                            rs.getInt(4)));
        });
        return result;
    }

    @Override
    public void replace(PlanType planType, List<PlanQuota> quotas) {
        jdbc.update(DELETE_SQL, planType.code());
        if (!quotas.isEmpty()) {
            jdbc.batchUpdate(INSERT_SQL, quotas.stream()
                    .map(q -> new Object[] {planType.code(), q.metric().name(), q.period().name(), q.limit()})
                    .toList());
        }
    }
}
