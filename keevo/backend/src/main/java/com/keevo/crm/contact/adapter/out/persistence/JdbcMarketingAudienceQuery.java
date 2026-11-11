package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.api.MarketingRecipient;
import com.keevo.crm.api.PurchaseFilter;
import com.keevo.crm.contact.domain.port.out.MarketingAudienceQuery;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 21.11 — {@link MarketingAudienceQuery} on the clients table and the single revenue source
 * ({@link RevenueQueryPort}): the purchases of a number are those of every client record with that number (any store,
 * archived included) — {@link ClientPurchaseAggregate} (shared with the client profile and the export, Story 20.2
 * reprise): count {@code SUM(doc_count)} (sales + invoices), net total {@code SUM(total_amount)} (credit notes
 * deducted), last purchase {@code MAX(COALESCE(recorded_at, created_at))} over the purchases (the clients-export
 * definitions, Story 20.14). With target stores, only their documents count ({@link RevenueQueryPort#storeScoped()}).
 *
 * <p>One statement: the eligible clients de-duplicated by phone (partial index {@code idx_clients_marketing_audience},
 * phone order) left-joined laterally to each number's aggregate — {@code idx_clients_phone_normalized} for the
 * records, then {@code r.client_id = pc.id} pushed into every branch of the source ({@code idx_sales_client_id},
 * {@code idx_invoices_client}, credit notes through their invoice) — filtered, ordered by phone, limited. Never a
 * query per client. Runs in a read-only transaction so the revenue source resolves in the tenant's search_path.
 */
@Component
public class JdbcMarketingAudienceQuery implements MarketingAudienceQuery {

    /** The 21.6 eligibility rule, the same constant as the unfiltered audience. */
    static final String ELIGIBLE = ClientSpringRepository.MARKETING_ELIGIBLE;

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcMarketingAudienceQuery(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarketingRecipient> audience(Collection<UUID> storeIds, PurchaseFilter filter, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT CAST(a.id AS text), a.name, a.phone_normalized, CAST(a.store_id AS text) "
                + from(storeIds, filter, args) + " ORDER BY a.phone_normalized LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, (rs, n) -> new MarketingRecipient(UUID.fromString(rs.getString(1)), rs.getString(2),
                rs.getString(3), rs.getString(4) == null ? null : UUID.fromString(rs.getString(4))), args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public int count(Collection<UUID> storeIds, PurchaseFilter filter, int cap) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT CAST(count(*) AS int) FROM (SELECT 1 " + from(storeIds, filter, args) + " LIMIT ?) x";
        args.add(cap);
        Integer n = jdbc.queryForObject(sql, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** {@code FROM … WHERE …} of the filtered audience; appends its bind values to {@code args} in text order. */
    String from(Collection<UUID> storeIds, PurchaseFilter filter, List<Object> args) {
        PurchaseFilter f = filter == null ? PurchaseFilter.NONE : filter;
        boolean stores = storeIds != null && !storeIds.isEmpty();
        String storeArray = stores ? storeIds.stream().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}")) : null;
        String documents = (stores ? revenue.storeScoped() : revenue.current()).documents();
        StringBuilder sql = new StringBuilder("FROM (SELECT DISTINCT ON (phone_normalized) id, name, phone_normalized, "
                + "store_id FROM clients WHERE " + ELIGIBLE);
        if (stores) {
            sql.append(" AND store_id = ANY(CAST(? AS uuid[]))");
            args.add(storeArray);
        }
        sql.append(" ORDER BY phone_normalized, updated_at DESC, id) a ")
                .append("LEFT JOIN LATERAL (SELECT COALESCE(SUM(d.").append(ClientPurchaseAggregate.COUNT)
                .append("), 0) AS n, COALESCE(SUM(d.").append(ClientPurchaseAggregate.TOTAL).append("), 0) AS spent, ")
                .append("MAX(d.").append(ClientPurchaseAggregate.LAST).append(") AS last_at FROM clients pc ")
                .append("CROSS JOIN LATERAL (SELECT ").append(ClientPurchaseAggregate.select("r")).append(" FROM ")
                .append(documents)
                .append(" r WHERE r.client_id = pc.id");
        if (stores) {
            sql.append(" AND r.store_id = ANY(CAST(? AS uuid[]))");
            args.add(storeArray);
        }
        sql.append(") d WHERE pc.phone_normalized = a.phone_normalized) p ON TRUE WHERE TRUE");
        if (f.noPurchaseSince() != null) {
            sql.append(" AND (p.last_at IS NULL OR p.last_at < ?)");
            args.add(Timestamp.from(f.noPurchaseSince()));
        }
        if (f.purchasedSince() != null) {
            sql.append(" AND p.last_at >= ?");
            args.add(Timestamp.from(f.purchasedSince()));
        }
        if (f.spentMin() != null) {
            sql.append(" AND p.spent >= ?");
            args.add(f.spentMin());
        }
        if (f.spentMax() != null) {
            sql.append(" AND p.spent <= ?");
            args.add(f.spentMax());
        }
        if (f.countMin() != null) {
            sql.append(" AND p.n >= ?");
            args.add(f.countMin());
        }
        if (f.countMax() != null) {
            sql.append(" AND p.n <= ?");
            args.add(f.countMax());
        }
        return sql.toString();
    }
}
