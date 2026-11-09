package com.keevo.payments.credit.adapter.out.persistence;

import com.keevo.payments.credit.domain.model.CreditMovement;
import com.keevo.payments.credit.domain.model.CreditMovementKind;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Story 21.3 — {@link AccountCreditStore} on {@code public.tenant_credits} and {@code public.tenant_credit_movements}
 * (V63). The balance change and its journal row are one statement (data-modifying CTE): the journal row exists exactly
 * when the balance moved. Lookups by primary key, the journal by {@code ix_tenant_credit_movements_tenant} (keyset).
 */
@Repository
public class JdbcAccountCreditStore implements AccountCreditStore {

    static final String BALANCE_SQL = "SELECT balance_xaf FROM public.tenant_credits WHERE tenant_id = ?";
    static final String LOCK_SQL = BALANCE_SQL + " FOR UPDATE";

    static final String DEBIT_SQL = """
            WITH c AS (
                UPDATE public.tenant_credits SET balance_xaf = balance_xaf - ?, updated_at = now()
                WHERE tenant_id = ? AND balance_xaf >= ?
                RETURNING tenant_id, balance_xaf
            ), logged AS (
                INSERT INTO public.tenant_credit_movements
                    (id, tenant_id, amount_xaf, balance_after_xaf, kind, payment_request_id, actor_id, reason)
                SELECT ?, c.tenant_id, ?, c.balance_xaf, ?, ?, ?, ? FROM c
            )
            SELECT balance_xaf FROM c
            """;

    static final String CREDIT_SQL = """
            WITH c AS (
                INSERT INTO public.tenant_credits AS t (tenant_id, balance_xaf, updated_at) VALUES (?, ?, now())
                ON CONFLICT (tenant_id) DO UPDATE SET balance_xaf = t.balance_xaf + EXCLUDED.balance_xaf,
                    updated_at = now()
                RETURNING t.tenant_id, t.balance_xaf
            ), logged AS (
                INSERT INTO public.tenant_credit_movements
                    (id, tenant_id, amount_xaf, balance_after_xaf, kind, payment_request_id, actor_id, reason)
                SELECT ?, c.tenant_id, ?, c.balance_xaf, ?, ?, ?, ? FROM c
            )
            SELECT balance_xaf FROM c
            """;

    static final String CONVERTED_SQL = "SELECT EXISTS (SELECT 1 FROM public.tenant_credit_movements "
            + "WHERE payment_request_id = ? AND kind = 'PAYMENT_TO_CREDIT')";

    static final String MOVEMENTS_SQL = """
            SELECT m.id, m.kind, m.amount_xaf, m.balance_after_xaf, m.payment_request_id, m.actor_id, m.reason,
                   m.created_at,
                   CASE WHEN r.kind = 'USAGE_PACK' THEN r.pack_label
                        WHEN r.id IS NOT NULL THEN r.plan_label || ' · ' || r.cycle_label END AS item_label,
                   NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '') AS actor_name
            FROM public.tenant_credit_movements m
            LEFT JOIN public.payment_requests r ON r.id = m.payment_request_id
            LEFT JOIN public.users u ON u.id = m.actor_id
            WHERE m.tenant_id = ? %s
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT ?
            """;

    static final String TENANT_EXISTS_SQL = "SELECT EXISTS (SELECT 1 FROM public.tenants WHERE id = ?)";

    private final JdbcTemplate jdbc;

    public JdbcAccountCreditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long balance(UUID tenantId) {
        List<Long> rows = jdbc.queryForList(BALANCE_SQL, Long.class, tenantId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Override
    public long lockBalance(UUID tenantId) {
        List<Long> rows = jdbc.queryForList(LOCK_SQL, Long.class, tenantId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Override
    public OptionalLong debit(UUID tenantId, long amount, CreditMovementKind kind, UUID paymentRequestId,
                              UUID actorId, String reason) {
        requirePositive(amount);
        List<Long> rows = jdbc.query(DEBIT_SQL, ps -> {
            ps.setLong(1, amount);
            ps.setObject(2, tenantId, Types.OTHER);
            ps.setLong(3, amount);
            ps.setObject(4, UUID.randomUUID(), Types.OTHER);
            ps.setLong(5, -amount);
            ps.setString(6, kind.name());
            ps.setObject(7, paymentRequestId, Types.OTHER);
            ps.setObject(8, actorId, Types.OTHER);
            ps.setString(9, reason);
        }, (rs, i) -> rs.getLong(1));
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.get(0));
    }

    @Override
    public long credit(UUID tenantId, long amount, CreditMovementKind kind, UUID paymentRequestId, UUID actorId,
                       String reason) {
        requirePositive(amount);
        return jdbc.query(CREDIT_SQL, ps -> {
            ps.setObject(1, tenantId, Types.OTHER);
            ps.setLong(2, amount);
            ps.setObject(3, UUID.randomUUID(), Types.OTHER);
            ps.setLong(4, amount);
            ps.setString(5, kind.name());
            ps.setObject(6, paymentRequestId, Types.OTHER);
            ps.setObject(7, actorId, Types.OTHER);
            ps.setString(8, reason);
        }, (rs, i) -> rs.getLong(1)).get(0);
    }

    @Override
    public boolean convertedToCredit(UUID paymentRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(CONVERTED_SQL, Boolean.class, paymentRequestId));
    }

    @Override
    public List<CreditMovement> movements(UUID tenantId, Instant beforeAt, UUID beforeId, int limit) {
        boolean keyset = beforeAt != null && beforeId != null;
        String sql = MOVEMENTS_SQL.formatted(keyset ? "AND (m.created_at, m.id) < (?, ?)" : "");
        return jdbc.query(sql, ps -> {
            int i = 1;
            ps.setObject(i++, tenantId, Types.OTHER);
            if (keyset) {
                ps.setTimestamp(i++, Timestamp.from(beforeAt));
                ps.setObject(i++, beforeId, Types.OTHER);
            }
            ps.setInt(i, limit);
        }, JdbcAccountCreditStore::map);
    }

    @Override
    public boolean tenantExists(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(TENANT_EXISTS_SQL, Boolean.class, tenantId));
    }

    private static CreditMovement map(ResultSet rs, int row) throws SQLException {
        return new CreditMovement(rs.getObject("id", UUID.class), CreditMovementKind.valueOf(rs.getString("kind")),
                rs.getLong("amount_xaf"), rs.getLong("balance_after_xaf"),
                rs.getObject("payment_request_id", UUID.class), rs.getString("item_label"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
    }
}
