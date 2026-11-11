package com.keevo.migration.adapter.out.persistence;

import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.ReconciliationFigures;
import com.keevo.migration.domain.model.ReconciliationFigures.Actual;
import com.keevo.migration.domain.model.ReconciliationFigures.ActualSubscription;
import com.keevo.migration.domain.model.ReconciliationFigures.Amount;
import com.keevo.migration.domain.model.ReconciliationFigures.Bucket;
import com.keevo.migration.domain.model.ReconciliationFigures.LinkState;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordCount;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordsSummary;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleField;
import com.keevo.migration.domain.port.out.ReconciliationTargetReader;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.8 — {@link ReconciliationTargetReader} over Keevo's database, read-only, schema-qualified SQL on the imported
 * tenant (validated schema name) and on the platform tables: an auditor's view of what is stored, independent of the
 * modules' code paths. A constant number of aggregate statements per owner (≈ 10), never one per row: one existence
 * probe of the module tables (a paid module's tables may be absent), one statement for the scalar counts, one per
 * grouped figure (documents, sales, expenses), one for the outstanding amounts, one for the sample (20 documents with
 * their line and payment aggregates), one for the link registry. Every access path is the tables' own primary key,
 * foreign-key or tenant index; the tenant tables are aggregated whole (the tenant holds only what the import wrote).
 */
@Component
class JdbcReconciliationTargetReader implements ReconciliationTargetReader {

    static final String RECORDS_SQL = """
            SELECT source_table, target_table, count(*) AS n, count(DISTINCT target_id) AS targets,
                   count(*) FILTER (WHERE note IS NOT NULL) AS noted, min(created_at) AS first_at
              FROM public.migration_records WHERE bref_owner_id = ? GROUP BY source_table, target_table
            """;

    static final String RECORDED_ACCOUNTS_SQL = """
            SELECT DISTINCT source_id FROM public.migration_records
             WHERE bref_owner_id = ? AND target_table = ANY (?::varchar[]) AND source_table = ?
            """;

    private static final List<String> TENANT_TABLES = List.of("stores", "products", "clients", "invoices",
            "invoice_items", "invoice_payments", "expenses");

    private final JdbcTemplate jdbc;

    JdbcReconciliationTargetReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RecordsSummary records(UUID brefOwnerId) {
        Map<String, RecordCount> pairs = new HashMap<>();
        jdbc.query(RECORDS_SQL, rs -> {
            Timestamp first = rs.getTimestamp("first_at");
            pairs.put(RecordsSummary.pair(rs.getString("source_table"), rs.getString("target_table")),
                    new RecordCount(rs.getLong("n"), rs.getLong("targets"), rs.getLong("noted"),
                            first == null ? null : first.toInstant()));
        }, brefOwnerId);
        Set<UUID> accounts = new HashSet<>(jdbc.query(con -> {
            var ps = con.prepareStatement(RECORDED_ACCOUNTS_SQL);
            ps.setObject(1, brefOwnerId);
            ps.setArray(2, con.createArrayOf("varchar", new Object[]{MigrationRecord.USERS,
                    MigrationRecord.LINKED_USERS, MigrationRecord.INVITATIONS}));
            ps.setString(3, MigrationRecord.BREF_USER);
            return ps;
        }, (rs, i) -> rs.getObject(1, UUID.class)));
        return new RecordsSummary(Map.copyOf(pairs), Set.copyOf(accounts));
    }

    @Override
    public Actual read(String tenantSchema, UUID tenantId, Collection<UUID> sampleIds, Collection<UUID> tokens) {
        String schema = TenantSchema.validate(tenantSchema);
        Set<String> present = present(schema);
        Map<String, Long> counts = new HashMap<>();
        long credit = scalars(schema, tenantId, present, counts);
        Amount collected = Amount.ZERO;
        Amount outstanding = Amount.ZERO;
        Map<Bucket, Amount> sales = new HashMap<>();
        Map<String, BigDecimal> expenses = new HashMap<>();
        Map<UUID, Map<SampleField, String>> sample = new HashMap<>();
        if (present.contains("invoices")) {
            documents(schema, counts);
            sales(schema, sales);
            if (present.contains("invoice_payments")) {
                collected = collected(schema);
                outstanding = outstanding(schema);
            }
            if (present.contains("invoice_items") && present.contains("invoice_payments") && !sampleIds.isEmpty()) {
                sample(schema, sampleIds, sample);
            }
        }
        if (present.contains("expenses")) {
            jdbc.query("SELECT to_char(expense_date, 'YYYY-MM') AS m, sum(amount) AS s FROM " + q(schema, "expenses")
                    + " WHERE archived_at IS NULL GROUP BY 1", rs -> {
                        expenses.put(rs.getString("m"), rs.getBigDecimal("s"));
                    });
        }
        Map<UUID, LinkState> links = tokens.isEmpty() ? Map.of() : links(schema, present.contains("invoices"), tokens);
        return new Actual(Map.copyOf(counts), Map.copyOf(sales), collected, Map.copyOf(expenses), outstanding,
                Map.copyOf(sample), links, new ActualSubscription(null, null, credit));
    }

    /** The module tables that exist in the tenant schema (one probe). */
    private Set<String> present(String schema) {
        Object[] names = TENANT_TABLES.stream().map(t -> q(schema, t)).toArray();
        Set<String> present = new HashSet<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT t FROM unnest(?::text[]) AS t WHERE to_regclass(t) IS NOT NULL");
            ps.setArray(1, con.createArrayOf("text", names));
            return ps;
        }, rs -> {
            String qualified = rs.getString(1);
            present.add(qualified.substring(qualified.lastIndexOf('.') + 1));
        });
        return present;
    }

    /** Scalar counts in one statement; returns the account credit balance. */
    private long scalars(String schema, UUID tenantId, Set<String> present, Map<String, Long> counts) {
        String sql = "SELECT " + countOf(schema, present, "stores") + " AS stores, "
                + countOf(schema, present, "products") + " AS products, "
                + countOf(schema, present, "clients") + " AS clients, "
                + countOf(schema, present, "invoice_items") + " AS lines, "
                + countOf(schema, present, "invoice_payments") + " AS payments, "
                + countOf(schema, present, "expenses") + " AS expenses, "
                + "(SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ? AND role = 'OWNER') "
                + "AS owner, "
                + "(SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ? AND role <> 'OWNER') "
                + "+ (SELECT count(*) FROM public.membership_invitations WHERE tenant_id = ? AND status <> 'ACCEPTED') "
                + "AS team, "
                + "(SELECT coalesce(max(balance_xaf), 0) FROM public.tenant_credits WHERE tenant_id = ?) AS credit";
        long[] credit = {0};
        jdbc.query(sql, rs -> {
            for (String key : List.of(ReconciliationFigures.STORES, ReconciliationFigures.PRODUCTS,
                    ReconciliationFigures.CLIENTS, ReconciliationFigures.LINES, ReconciliationFigures.PAYMENTS,
                    ReconciliationFigures.EXPENSES, ReconciliationFigures.OWNER, ReconciliationFigures.TEAM)) {
                counts.put(key, rs.getLong(key));
            }
            credit[0] = rs.getLong("credit");
        }, tenantId, tenantId, tenantId, tenantId);
        return credit[0];
    }

    private static String countOf(String schema, Set<String> present, String table) {
        return present.contains(table) ? "(SELECT count(*) FROM " + q(schema, table) + ")" : "0";
    }

    private void documents(String schema, Map<String, Long> counts) {
        jdbc.query("SELECT kind, status, deleted_at IS NOT NULL AS deleted, count(*) AS n FROM "
                + q(schema, "invoices") + " GROUP BY 1, 2, 3", rs -> {
                    String key = rs.getBoolean("deleted") ? ReconciliationFigures.DELETED_DOCUMENTS
                            : ReconciliationFigures.documents(rs.getString("kind"), rs.getString("status"));
                    counts.merge(key, rs.getLong("n"), Long::sum);
                });
    }

    private void sales(String schema, Map<Bucket, Amount> sales) {
        jdbc.query("SELECT store_id, to_char(issue_date, 'YYYY-MM') AS m, sum(total_ttc) AS s, count(*) AS n FROM "
                + q(schema, "invoices") + " WHERE kind = 'INVOICE' AND status <> 'CANCELLED' AND deleted_at IS NULL "
                + "GROUP BY 1, 2", rs -> {
                    sales.put(new Bucket(rs.getObject("store_id", UUID.class), rs.getString("m")),
                            new Amount(rs.getBigDecimal("s"), rs.getLong("n")));
                });
    }

    private Amount collected(String schema) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) AS s, count(*) AS n FROM "
                + q(schema, "invoice_payments") + " WHERE paid_at IS NOT NULL",
                (rs, i) -> new Amount(rs.getBigDecimal("s"), rs.getLong("n")));
    }

    /** Live ISSUED invoices: Σ max(0, total − paid rows); items = the documents and their paid rows. */
    private Amount outstanding(String schema) {
        return jdbc.queryForObject("SELECT coalesce(sum(greatest(0, i.total_ttc - coalesce(p.paid, 0))), 0) AS s, "
                + "count(*) + coalesce(sum(p.n), 0) AS n FROM " + q(schema, "invoices") + " i LEFT JOIN "
                + "(SELECT invoice_id, sum(amount) AS paid, count(*) AS n FROM " + q(schema, "invoice_payments")
                + " WHERE paid_at IS NOT NULL GROUP BY invoice_id) p ON p.invoice_id = i.id "
                + "WHERE i.kind = 'INVOICE' AND i.status = 'ISSUED' AND i.deleted_at IS NULL",
                (rs, i) -> new Amount(rs.getBigDecimal("s"), rs.getLong("n")));
    }

    /** The sampled documents with their line and payment aggregates, one statement. */
    private void sample(String schema, Collection<UUID> ids, Map<UUID, Map<SampleField, String>> out) {
        String sql = "SELECT i.id, i.kind, i.status, i.number, i.issue_date, i.store_id, i.client_name, i.subtotal, "
                + "i.discount_amount, i.delivery_fee, i.total_vat, i.total_ttc, i.legacy_public_token, "
                + "i.deleted_at IS NOT NULL AS deleted, coalesce(l.n, 0) AS line_count, coalesce(l.ht, 0) AS lines_ht, "
                + "coalesce(p.n, 0) AS payment_count, coalesce(p.paid, 0) AS paid FROM " + q(schema, "invoices")
                + " i LEFT JOIN (SELECT invoice_id, count(*) AS n, sum(total_ht) AS ht FROM "
                + q(schema, "invoice_items") + " WHERE invoice_id = ANY (?) GROUP BY invoice_id) l "
                + "ON l.invoice_id = i.id LEFT JOIN (SELECT invoice_id, count(*) AS n, "
                + "sum(amount) FILTER (WHERE paid_at IS NOT NULL) AS paid FROM " + q(schema, "invoice_payments")
                + " WHERE invoice_id = ANY (?) GROUP BY invoice_id) p ON p.invoice_id = i.id WHERE i.id = ANY (?)";
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            Array array = uuids(con, ids);
            ps.setArray(1, array);
            ps.setArray(2, array);
            ps.setArray(3, array);
            return ps;
        }, rs -> {
            Map<SampleField, String> f = new EnumMap<>(SampleField.class);
            f.put(SampleField.KIND, text(rs.getString("kind")));
            f.put(SampleField.STATUS, text(rs.getString("status")));
            f.put(SampleField.NUMBER, text(rs.getString("number")));
            f.put(SampleField.ISSUE_DATE, text(rs.getString("issue_date")));
            f.put(SampleField.STORE, text(rs.getString("store_id")));
            f.put(SampleField.CLIENT_NAME, text(rs.getString("client_name")));
            f.put(SampleField.SUBTOTAL, Long.toString(rs.getLong("subtotal")));
            f.put(SampleField.DISCOUNT, Long.toString(rs.getLong("discount_amount")));
            f.put(SampleField.DELIVERY, Long.toString(rs.getLong("delivery_fee")));
            f.put(SampleField.TAX, Long.toString(rs.getLong("total_vat")));
            f.put(SampleField.TOTAL, Long.toString(rs.getLong("total_ttc")));
            f.put(SampleField.LINE_COUNT, Long.toString(rs.getLong("line_count")));
            f.put(SampleField.LINES_TOTAL, Long.toString(rs.getLong("lines_ht")));
            f.put(SampleField.PAYMENT_COUNT, Long.toString(rs.getLong("payment_count")));
            f.put(SampleField.PAID, Long.toString(rs.getLong("paid")));
            f.put(SampleField.PUBLIC_LINK, text(rs.getString("legacy_public_token")));
            f.put(SampleField.DELETED, Boolean.toString(rs.getBoolean("deleted")));
            out.put(rs.getObject("id", UUID.class), f);
        });
    }

    /** The registry rows of the tokens: same tenant, and the document they point to is live. */
    private Map<UUID, LinkState> links(String schema, boolean invoices, Collection<UUID> tokens) {
        String live = invoices ? "(i.id IS NOT NULL AND i.deleted_at IS NULL)" : "false";
        String join = invoices ? " LEFT JOIN " + q(schema, "invoices") + " i ON i.id = l.invoice_id" : "";
        Map<UUID, LinkState> links = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT l.token, l.invoice_id, l.tenant_schema = ? AS same_tenant, " + live
                    + " AS live FROM public.invoice_legacy_links l" + join + " WHERE l.token = ANY (?)");
            ps.setString(1, schema);
            ps.setArray(2, uuids(con, tokens));
            return ps;
        }, rs -> {
            links.put(rs.getObject("token", UUID.class), new LinkState(rs.getObject("invoice_id", UUID.class),
                    rs.getBoolean("same_tenant"), rs.getBoolean("live")));
        });
        return Map.copyOf(links);
    }

    private static Array uuids(Connection con, Collection<UUID> ids) throws SQLException {
        return con.createArrayOf("uuid", ids.toArray());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** {@code "schema".table} — the schema is re-validated here (also validated in {@link #read}). */
    private static String q(String schema, String table) {
        String valid = TenantSchema.validate(schema);
        return "\"" + valid + "\"." + table;
    }
}
