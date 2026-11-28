package com.keevo.commerce.sale.adapter.out.persistence.impl;

import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Story P03 — {@link SalesSummaryPort} on {@link RevenueQueryPort}: one aggregate statement, the period / store /
 * seller predicates pushed by PostgreSQL into each branch of the source (the dashboard's {@code occurred_at} / store
 * indexes). Every store: {@link RevenueQueryPort#current()}; one store: {@link RevenueQueryPort#storeScoped()}. Runs in
 * a read-only transaction so the source resolves in the tenant's search_path.
 */
@Component
public class JdbcSalesSummary implements SalesSummaryPort {

    static final String SELECT = "SELECT COALESCE(SUM(r.total_amount), 0) AS amount, "
            + "COALESCE(SUM(r.doc_count), 0) AS doc_count, COALESCE(SUM(r.paid_amount), 0) AS paid_amount FROM ";

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcSalesSummary(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summarize(Instant from, Instant to, UUID storeId, UUID employeeId) {
        String documents = (storeId == null ? revenue.current() : revenue.storeScoped()).documents();
        StringBuilder sql = new StringBuilder(SELECT).append(documents)
                .append(" r WHERE r.occurred_at >= ? AND r.occurred_at < ?");
        List<Object> args = new ArrayList<>(4);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (storeId != null) {
            sql.append(" AND r.store_id = ?");
            args.add(storeId);
        }
        if (employeeId != null) {
            sql.append(" AND r.employee_id = ?");
            args.add(employeeId);
        }
        Summary summary = jdbc.queryForObject(sql.toString(),
                (rs, n) -> new Summary(rs.getLong("amount"), rs.getLong("doc_count"), rs.getLong("paid_amount")),
                args.toArray());
        return summary == null ? Summary.NONE : summary;
    }
}
