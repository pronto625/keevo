package com.keevo.admin.subscription.adapter.out.persistence;

import com.keevo.admin.subscription.domain.model.PaymentMethod;
import com.keevo.admin.subscription.domain.model.TenantPayment;
import com.keevo.admin.subscription.domain.port.out.TenantPaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JdbcTenantPaymentRepository — {@link TenantPaymentRepository} on {@code public.tenant_payments} (Story 9-2). */
@Repository
public class JdbcTenantPaymentRepository implements TenantPaymentRepository {

    static final String INSERT_SQL =
            "INSERT INTO public.tenant_payments "
            + "(id, tenant_id, amount_xaf, method, paid_at, period_start, period_end, note, recorded_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String COLUMNS =
            "id, tenant_id, amount_xaf, method, paid_at, period_start, period_end, note, recorded_by, "
            + "voided_at, void_reason ";

    static final String FIND_BY_TENANT_SQL =
            "SELECT " + COLUMNS
            + "FROM public.tenant_payments WHERE tenant_id = ? ORDER BY paid_at DESC, created_at DESC";

    static final String FIND_BY_ID_SQL =
            "SELECT " + COLUMNS + "FROM public.tenant_payments WHERE tenant_id = ? AND id = ?";

    static final String VOID_SQL =
            "UPDATE public.tenant_payments SET voided_at = ?, voided_by = ?, void_reason = ? "
            + "WHERE tenant_id = ? AND id = ? AND voided_at IS NULL";

    private final JdbcTemplate jdbc;

    public JdbcTenantPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantPayment p) {
        jdbc.update(INSERT_SQL,
                p.id(), p.tenantId(), Math.toIntExact(p.amountXaf()), p.method().name(),
                Timestamp.from(p.paidAt()), Date.valueOf(p.periodStart()), Date.valueOf(p.periodEnd()),
                p.note(), p.recordedBy());
    }

    @Override
    public List<TenantPayment> findByTenant(UUID tenantId) {
        return jdbc.query(FIND_BY_TENANT_SQL, JdbcTenantPaymentRepository::map, tenantId);
    }

    @Override
    public Optional<TenantPayment> findById(UUID tenantId, UUID paymentId) {
        return jdbc.query(FIND_BY_ID_SQL, JdbcTenantPaymentRepository::map, tenantId, paymentId)
                .stream().findFirst();
    }

    @Override
    public boolean voidPayment(UUID tenantId, UUID paymentId, UUID actorId, String reason, Instant at) {
        return jdbc.update(VOID_SQL, Timestamp.from(at), actorId, reason, tenantId, paymentId) == 1;
    }

    private static TenantPayment map(ResultSet rs, int row) throws SQLException {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new TenantPayment(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getLong("amount_xaf"),
                PaymentMethod.valueOf(rs.getString("method")),
                rs.getTimestamp("paid_at").toInstant(),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getString("note"),
                UUID.fromString(rs.getString("recorded_by")),
                voidedAt != null ? voidedAt.toInstant() : null,
                rs.getString("void_reason"));
    }
}
