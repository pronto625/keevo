package com.keevo.migration.adapter.out.source;

import com.keevo.migration.domain.model.CutoverReport.IndexProbe;
import com.keevo.migration.domain.port.out.BrefOwnerDirectory;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.9 — {@link BrefOwnerDirectory} over the restored Bref database, through the read-only pool of
 * {@link JdbcBrefSource}: owners by primary-key keyset (one index range scan per page), and a probe of the helper
 * indexes the runbook creates on the copy before it becomes read-only ({@code keevo/ops/migration/bref-copy-indexes.sql}).
 */
@Component
class JdbcBrefOwnerDirectory implements BrefOwnerDirectory {

    /** Deleted Bref accounts are never migrated (decision D1 of 24.9). */
    static final String OWNERS_SQL = "SELECT src.id FROM \"user\" src WHERE src.role = 'owner' "
            + "AND src.\"deletedAt\" IS NULL AND src.id > ?::uuid ORDER BY src.id LIMIT ?";

    /** 00000000-0000-0000-0000-000000000000: below every uuid in PostgreSQL's (unsigned, bytewise) order. */
    static final UUID FIRST = new UUID(0L, 0L);

    /**
     * The helper indexes of the runbook script, by name, with their table: expected when the table exists, present
     * when the index exists.
     */
    static final String PROBE_SQL = """
            SELECT count(*) FILTER (WHERE to_regclass('public.' || quote_ident(x.tbl)) IS NOT NULL) AS expected,
                   count(*) FILTER (WHERE to_regclass('public.' || x.idx) IS NOT NULL) AS present
              FROM (VALUES ('keevo_bref_shop_user', 'shop'), ('keevo_bref_user_shop', 'user'),
                           ('keevo_bref_product_shop', 'product'), ('keevo_bref_invoice_shop', 'invoice'),
                           ('keevo_bref_invoice_item_invoice', 'invoice_item'),
                           ('keevo_bref_invoice_payment_invoice', 'invoice_payment'),
                           ('keevo_bref_expense_shop', 'expense'), ('keevo_bref_subscription_owner', 'subscription'),
                           ('keevo_bref_plan_request_owner', 'plan_payment_request'),
                           ('keevo_bref_sms_recharge_user', 'sms_recharge_request'),
                           ('keevo_bref_ai_conversation_user', 'ai_conversation')) AS x(idx, tbl)
            """;

    private final JdbcBrefSource source;

    JdbcBrefOwnerDirectory(JdbcBrefSource source) {
        this.source = source;
    }

    @Override
    public boolean available() {
        return source.available();
    }

    @Override
    public List<UUID> owners(UUID after, int limit) {
        return source.readOnly(c -> {
            try (PreparedStatement ps = c.prepareStatement(OWNERS_SQL)) {
                ps.setString(1, (after == null ? FIRST : after).toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<UUID> ids = new ArrayList<>(limit);
                    while (rs.next()) {
                        ids.add(rs.getObject(1, UUID.class));
                    }
                    return ids;
                }
            }
        });
    }

    @Override
    public IndexProbe helperIndexes() {
        return source.readOnly(c -> {
            try (PreparedStatement ps = c.prepareStatement(PROBE_SQL); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new IndexProbe(rs.getInt("present"), rs.getInt("expected"));
            }
        });
    }
}
