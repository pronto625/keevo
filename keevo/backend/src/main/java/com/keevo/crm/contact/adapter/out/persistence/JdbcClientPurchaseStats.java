package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.domain.port.out.ClientPurchaseStatsPort;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 20.2 (reprise 11/11/2026) — {@link ClientPurchaseStatsPort} on the single revenue source
 * ({@link RevenueQueryPort}) with the shared {@link ClientPurchaseAggregate}: one statement, {@code client_id = ?}
 * pushed into every branch of the source ({@code idx_sales_client_id}, {@code idx_invoices_client}, credit notes
 * through {@code idx_credit_notes_invoice}). With a store set: the store-scoped relations and
 * {@code store_id = ANY(?)} (a document without store matches no store). Runs in a read-only transaction so the
 * source resolves in the tenant's search_path (no schema interpolation).
 */
@Component
public class JdbcClientPurchaseStats implements ClientPurchaseStatsPort {

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcClientPurchaseStats(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    @Transactional(readOnly = true)
    public Stats statsOf(UUID clientId, Set<UUID> storeIds) {
        if (storeIds != null && storeIds.isEmpty()) {
            return Stats.NONE; // no visible store: nothing to read
        }
        String documents = (storeIds == null ? revenue.current() : revenue.storeScoped()).documents();
        StringBuilder sql = new StringBuilder("SELECT ").append(ClientPurchaseAggregate.select("r"))
                .append(" FROM ").append(documents).append(" r WHERE r.client_id = ?");
        Object[] args;
        if (storeIds == null) {
            args = new Object[]{clientId};
        } else {
            sql.append(" AND r.store_id = ANY(CAST(? AS uuid[]))");
            args = new Object[]{clientId, storeIds.stream().map(UUID::toString)
                    .collect(Collectors.joining(",", "{", "}"))};
        }
        Stats stats = jdbc.queryForObject(sql.toString(), (rs, n) -> {
            Timestamp last = rs.getTimestamp(ClientPurchaseAggregate.LAST);
            return new Stats(rs.getLong(ClientPurchaseAggregate.COUNT), rs.getLong(ClientPurchaseAggregate.TOTAL),
                    last == null ? null : last.toInstant());
        }, args);
        return stats == null ? Stats.NONE : stats;
    }
}
