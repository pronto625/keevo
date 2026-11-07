package com.keevo.reporting.leaderboard.adapter.out.persistence;

import com.keevo.reporting.leaderboard.domain.port.out.SellerLeaderboardRepository;
import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;
import com.keevo.shared.infrastructure.export.ExportValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JdbcSellerLeaderboardRepository — SQL adapter of the seller leaderboard (v1s-14-2).
 *
 * <p>Story 20.9 (R4): reads the revenue source ({@link RevenueQueryPort}) — sales by their actor, invoices by their
 * creator (Bref {@code userId}), credit notes deducted from the seller of their invoice; documents carry no store, so
 * a store filter keeps sales only.
 *
 * <p>One query: revenue documents grouped by {@code employee_id} (the actor's user id) in the
 * WAT window {@code [from 00:00 WAT, to+1 00:00 WAT)} — same convention as
 * {@code JdbcProfitabilityRepository} — UNION the active employees (store-scoped when a store is
 * given) who did not sell. Names come from {@link ExportValues#actorJoins} (latest employee row,
 * else « Propriétaire » for an OWNER user), else « Vendeur inconnu ». Tenant isolation is the
 * request's search_path.
 */
@Repository
public class JdbcSellerLeaderboardRepository implements SellerLeaderboardRepository {

    /** Shop time zone — same convention as JdbcProfitabilityRepository. */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    static final String UNKNOWN_SELLER = "Vendeur inconnu";

    private static final String ALIAS = "lb";

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcSellerLeaderboardRepository(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    public List<RawSellerRow> findRawSellers(LocalDate from, LocalDate to, UUID storeId) {
        Timestamp tsFrom = Timestamp.from(from.atStartOfDay(WAT).toInstant());
        Timestamp tsTo   = Timestamp.from(to.plusDays(1).atStartOfDay(WAT).toInstant());

        String saleStoreFilter = storeId == null ? "" : " AND s.store_id = ?::uuid";
        // 20.9: one resolution; a store filter reads the sales-only relation (documents carry no store)
        String docs = (storeId == null ? revenue.current() : revenue.storeScoped()).documents();
        String employeeStoreFilter = storeId == null ? "" : " AND e.store_id = ?::uuid";

        String sql =
            "WITH sold AS (" +
            "  SELECT s.employee_id AS seller_id, COALESCE(SUM(s.doc_count), 0)::int AS sales_count," +
            "         COALESCE(SUM(s.total_amount), 0)::bigint AS total_revenue" +
            "  FROM " + docs + " s" +
            "  WHERE s.occurred_at >= ? AND s.occurred_at < ? AND s.employee_id IS NOT NULL" +
            saleStoreFilter +
            "  GROUP BY s.employee_id" +
            "), sellers AS (" +
            "  SELECT seller_id, sales_count, total_revenue FROM sold" +
            "  UNION ALL" +
            "  SELECT DISTINCT e.user_id, 0, 0::bigint FROM employees e" +
            "  WHERE e.status = 'ACTIVE'" + employeeStoreFilter +
            "    AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.seller_id = e.user_id)" +
            ") " +
            "SELECT x.seller_id, x.sales_count, x.total_revenue, " +
            "  COALESCE(" + ExportValues.actorNameSelect(ALIAS) + ", '" + UNKNOWN_SELLER + "') AS seller_name, " +
            "  COALESCE(" + ALIAS + "_m.role = 'OWNER', FALSE) AS is_owner " + // 19.6 — role in this company
            "FROM sellers x" +
            ExportValues.actorJoins("x.seller_id", ALIAS);

        List<Object> args = new ArrayList<>();
        args.add(tsFrom);
        args.add(tsTo);
        if (storeId != null) {
            args.add(storeId.toString());
            args.add(storeId.toString());
        }

        return jdbc.query(sql, (rs, rowNum) -> new RawSellerRow(
                UUID.fromString(rs.getString("seller_id")),
                rs.getString("seller_name"),
                rs.getBoolean("is_owner"),
                rs.getInt("sales_count"),
                rs.getLong("total_revenue")
        ), args.toArray());
    }
}
