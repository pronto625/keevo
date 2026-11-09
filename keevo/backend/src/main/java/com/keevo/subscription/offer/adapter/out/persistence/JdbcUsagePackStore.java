package com.keevo.subscription.offer.adapter.out.persistence;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.subscription.offer.domain.model.UsagePack;
import com.keevo.subscription.offer.domain.port.out.UsagePackStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Story 21.3 — {@link UsagePackStore} over {@code public.usage_packs} (V63; every lookup by primary key). */
@Repository
public class JdbcUsagePackStore implements UsagePackStore {

    private static final String SELECT = "SELECT code, label, metric, quantity, price_xaf, active, sort_order, "
            + "updated_at FROM public.usage_packs ";

    private static final String UPSERT = "INSERT INTO public.usage_packs "
            + "(code, label, metric, quantity, price_xaf, active, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, metric = EXCLUDED.metric, "
            + "quantity = EXCLUDED.quantity, price_xaf = EXCLUDED.price_xaf, active = EXCLUDED.active, "
            + "sort_order = EXCLUDED.sort_order, updated_at = now()";

    private final JdbcTemplate jdbc;

    public JdbcUsagePackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UsagePack> all() {
        return jdbc.query(SELECT + "ORDER BY sort_order, metric, quantity, code", JdbcUsagePackStore::map);
    }

    @Override
    public Optional<UsagePack> find(String code) {
        return jdbc.query(SELECT + "WHERE code = ?", JdbcUsagePackStore::map, code).stream().findFirst();
    }

    @Override
    public Optional<UsagePack> lock(String code) {
        return jdbc.query(SELECT + "WHERE code = ? FOR UPDATE", JdbcUsagePackStore::map, code).stream().findFirst();
    }

    @Override
    public void upsert(UsagePack p) {
        jdbc.update(UPSERT, p.code(), p.label(), p.metric().name(), p.quantity(), p.priceXaf(), p.active(),
                p.sortOrder());
    }

    private static UsagePack map(ResultSet rs, int row) throws SQLException {
        return new UsagePack(rs.getString("code"), rs.getString("label"), UsageMetric.valueOf(rs.getString("metric")),
                rs.getInt("quantity"), rs.getLong("price_xaf"), rs.getBoolean("active"), rs.getInt("sort_order"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
