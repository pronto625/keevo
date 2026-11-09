package com.keevo.subscription.offer.adapter.out.persistence;

import com.keevo.subscription.offer.domain.model.StoreProductRow;
import com.keevo.subscription.offer.domain.port.out.StoreProductStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Story 21.2 — {@link StoreProductStore} over {@code public.store_products} (primary key {@code (store, product_id)};
 * the cycle and the grid price joined by their primary keys).
 */
@Repository
public class JdbcStoreProductStore implements StoreProductStore {

    private static final String SELECT = "SELECT sp.store, sp.product_id, sp.plan_code, sp.cycle_code, "
            + "c.label AS cycle_label, c.months, sp.active, sp.declared_price_xaf, sp.observed_price, "
            + "sp.observed_currency, sp.observed_price_xaf, sp.observed_at, pp.amount_xaf::bigint AS grid_price_xaf, "
            + "sp.updated_at, c.archived_at IS NOT NULL AS cycle_archived "
            + "FROM public.store_products sp "
            + "JOIN public.billing_cycles c ON c.code = sp.cycle_code "
            + "LEFT JOIN public.plan_prices pp ON pp.plan_code = sp.plan_code AND pp.cycle_code = sp.cycle_code ";

    private static final String UPSERT = "INSERT INTO public.store_products "
            + "(store, product_id, plan_code, cycle_code, declared_price_xaf, active) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (store, product_id) DO UPDATE SET plan_code = EXCLUDED.plan_code, "
            + "cycle_code = EXCLUDED.cycle_code, declared_price_xaf = EXCLUDED.declared_price_xaf, "
            + "active = EXCLUDED.active, updated_at = now()";

    private static final String OBSERVED = "UPDATE public.store_products SET observed_price = ?, observed_currency = ?, "
            + "observed_price_xaf = ?, observed_at = ? "
            + "WHERE store = ? AND product_id = ? AND (observed_at IS NULL OR observed_at <= ?) "
            // only a new price is written: renewals at the same price take no row lock on this shared row
            + "AND (observed_price IS DISTINCT FROM ? OR observed_currency IS DISTINCT FROM ?)";

    private final JdbcTemplate jdbc;

    public JdbcStoreProductStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoreProductRow> all() {
        return jdbc.query(SELECT + "ORDER BY sp.store, sp.plan_code, c.sort_order, sp.product_id",
                JdbcStoreProductStore::map);
    }

    @Override
    public Optional<StoreProductRow> find(String store, String productId) {
        return jdbc.query(SELECT + "WHERE sp.store = ? AND sp.product_id = ?", JdbcStoreProductStore::map, store,
                productId).stream().findFirst();
    }

    @Override
    public Optional<StoreProductRow> lock(String store, String productId) {
        return jdbc.query(SELECT + "WHERE sp.store = ? AND sp.product_id = ? FOR UPDATE OF sp",
                JdbcStoreProductStore::map, store, productId).stream().findFirst();
    }

    @Override
    public void upsert(String store, String productId, String planCode, String cycleCode, Long declaredPriceXaf,
                       boolean active) {
        jdbc.update(UPSERT, ps -> {
            ps.setString(1, store);
            ps.setString(2, productId);
            ps.setString(3, planCode);
            ps.setString(4, cycleCode);
            ps.setObject(5, declaredPriceXaf, Types.BIGINT);
            ps.setBoolean(6, active);
        });
    }

    @Override
    public void recordObservedPrice(String store, String productId, BigDecimal amount, String currency, Long amountXaf,
                                    Instant at) {
        jdbc.update(OBSERVED, ps -> {
            ps.setBigDecimal(1, amount);
            ps.setString(2, currency);
            ps.setObject(3, amountXaf, Types.BIGINT);
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setString(5, store);
            ps.setString(6, productId);
            ps.setTimestamp(7, Timestamp.from(at));
            ps.setBigDecimal(8, amount);
            ps.setString(9, currency);
        });
    }

    private static StoreProductRow map(ResultSet rs, int row) throws SQLException {
        Timestamp observedAt = rs.getTimestamp("observed_at");
        return new StoreProductRow(rs.getString("store"), rs.getString("product_id"), rs.getString("plan_code"),
                rs.getString("cycle_code"), rs.getString("cycle_label"), rs.getInt("months"), rs.getBoolean("active"),
                rs.getObject("declared_price_xaf", Long.class), rs.getBigDecimal("observed_price"),
                rs.getString("observed_currency"), rs.getObject("observed_price_xaf", Long.class),
                observedAt == null ? null : observedAt.toInstant(), rs.getObject("grid_price_xaf", Long.class),
                rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("cycle_archived"));
    }
}
