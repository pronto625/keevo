package com.keevo.payments.checkout.adapter.out.persistence;

import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentKind;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.model.PlanPeriod;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.1 — {@link PaymentRequestStore} on {@code public.payment_requests} (V61) and the ledger row in
 * {@code public.tenant_payments}. Every lookup goes through an index: primary key, {@code uq_payment_requests_in_flight}
 * (in-flight request of a company, stale expiry), {@code uq_payment_requests_provider_ref} (webhook routing). Every
 * transition is one guarded UPDATE.
 */
@Repository
public class JdbcPaymentRequestStore implements PaymentRequestStore {

    private static final String COLUMNS = "id, tenant_id, tenant_schema, requested_by, provider, method, phone, "
            + "plan_code, plan_label, cycle_code, cycle_label, months, base_xaf, reduction_xaf, amount_due_xaf, "
            + "currency, discount_id, discount_label, promo_code, priced_at, status, next_step, ussd_code, "
            + "app_transaction_ref, provider_transaction_ref, failure_reason, tenant_payment_id, period_start, "
            + "period_end, expires_at, confirmed_at, created_at, kind, credit_applied_xaf, pack_code, pack_label, "
            + "pack_metric, pack_quantity";

    /**
     * Story 21.3 — wraps a transition out of the in-flight states ({@code UPDATE … RETURNING id, tenant_id,
     * credit_applied_xaf}) so that the credit reserved by the moved request goes back to the company in the SAME
     * statement (balance + journal row {@code PAYMENT_RELEASE}). One request in flight per company (unique index), so
     * at most one moved row per company; the balance is still added per company (sum) to stay exact. Returns the
     * number of moved requests.
     */
    static String releasingCredit(String transition) {
        return "WITH moved AS (" + transition + " RETURNING id, tenant_id, credit_applied_xaf), "
                + "refund AS (UPDATE public.tenant_credits c SET balance_xaf = c.balance_xaf + m.total, "
                + "updated_at = now() FROM (SELECT tenant_id, sum(credit_applied_xaf) AS total FROM moved "
                + "WHERE credit_applied_xaf > 0 GROUP BY tenant_id) m WHERE c.tenant_id = m.tenant_id "
                + "RETURNING c.tenant_id, c.balance_xaf), "
                + "logged AS (INSERT INTO public.tenant_credit_movements "
                + "(id, tenant_id, amount_xaf, balance_after_xaf, kind, payment_request_id) "
                + "SELECT gen_random_uuid(), m.tenant_id, m.credit_applied_xaf, r.balance_xaf, 'PAYMENT_RELEASE', m.id "
                + "FROM moved m JOIN refund r ON r.tenant_id = m.tenant_id WHERE m.credit_applied_xaf > 0) "
                + "SELECT count(*) FROM moved";
    }

    static final String EXPIRE_STALE_SQL = releasingCredit("UPDATE public.payment_requests SET status = 'EXPIRED', "
            + "failure_reason = ?, updated_at = now() WHERE tenant_id = ? AND status IN ('INITIATING', 'PENDING') "
            + "AND ((status = 'INITIATING' AND updated_at < ?) OR (status = 'PENDING' AND updated_at < ?))");
    static final String INSERT_SQL = "INSERT INTO public.payment_requests (" + COLUMNS + ", updated_at) VALUES "
            + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, now())";
    static final String PROVIDER_ANSWER_SQL = "UPDATE public.payment_requests SET provider_transaction_ref = ?, "
            + "provider_status = ?, "
            + "next_step = CASE WHEN status IN ('INITIATING', 'PENDING') THEN ? ELSE next_step END, "
            + "ussd_code = CASE WHEN status IN ('INITIATING', 'PENDING') THEN ? ELSE ussd_code END, "
            + "status = CASE WHEN status = 'INITIATING' THEN 'PENDING' ELSE status END, updated_at = now() "
            + "WHERE id = ?";
    static final String STEP_SQL = "UPDATE public.payment_requests SET next_step = ?, ussd_code = ?, "
            + "provider_status = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'";
    static final String FAIL_SQL = releasingCredit("UPDATE public.payment_requests SET status = 'FAILED', "
            + "failure_reason = ?, provider_status = COALESCE(?, provider_status), next_step = 'NONE', "
            + "updated_at = now() WHERE id = ? AND status = ANY (?) AND status IN ('INITIATING', 'PENDING')");
    static final String CANCEL_SQL = releasingCredit("UPDATE public.payment_requests SET status = 'CANCELLED', "
            + "failure_reason = ?, next_step = 'NONE', updated_at = now() WHERE id = ? AND tenant_id = ? "
            + "AND status IN ('INITIATING', 'PENDING')");
    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM public.payment_requests WHERE id = ?";
    static final String FIND_OF_TENANT_SQL = FIND_SQL + " AND tenant_id = ?";
    static final String LOCK_SQL = FIND_SQL + " FOR UPDATE";
    static final String IN_FLIGHT_SQL = "SELECT " + COLUMNS + " FROM public.payment_requests "
            + "WHERE tenant_id = ? AND status IN ('INITIATING', 'PENDING')";
    static final String BY_PROVIDER_REF_SQL = "SELECT " + COLUMNS + " FROM public.payment_requests "
            + "WHERE provider = ? AND provider_transaction_ref = ?";
    static final String BY_APP_REF_SQL = "SELECT " + COLUMNS + " FROM public.payment_requests "
            + "WHERE app_transaction_ref = ?";
    static final String ATTACH_REF_SQL = "UPDATE public.payment_requests SET provider_transaction_ref = ?, "
            + "updated_at = now() WHERE id = ? AND provider_transaction_ref IS NULL";
    static final String COUNT_SINCE_SQL = "SELECT count(*) FROM public.payment_requests "
            + "WHERE tenant_id = ? AND created_at >= ?";
    /** Story 21.3 — a free offer is a 100 % discount (a request covered by the credit is not one). */
    static final String FREE_TAKEN_SQL = "SELECT EXISTS (SELECT 1 FROM public.payment_requests WHERE tenant_id = ? "
            + "AND discount_id = ? AND status = 'CONFIRMED' AND reduction_xaf = base_xaf)";
    /** Story 21.3 — a late payment added to the credit: the request keeps its status, the reason tells the owner. */
    static final String CREDITED_SQL = "UPDATE public.payment_requests SET failure_reason = ?, "
            + "provider_status = 'SUCCESS', next_step = 'NONE', updated_at = now() WHERE id = ? AND status <> 'CONFIRMED'";
    static final String CONFIRM_SQL = "UPDATE public.payment_requests SET status = 'CONFIRMED', confirmed_at = ?, "
            + "tenant_payment_id = ?, period_start = ?, period_end = ?, expires_at = ?, next_step = 'NONE', "
            + "failure_reason = NULL, provider_status = CASE WHEN provider = 'NONE' THEN provider_status "
            + "ELSE 'SUCCESS' END, updated_at = now() WHERE id = ? AND status <> 'CONFIRMED'";
    static final String LEDGER_SQL = "INSERT INTO public.tenant_payments (id, tenant_id, amount_xaf, method, paid_at, "
            + "period_start, period_end, note, recorded_by, plan_code, cycle_code, payment_request_id) "
            + "VALUES (?, ?, ?, 'MOBILE_MONEY', ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public JdbcPaymentRequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int expireStale(UUID tenantId, Instant initiatingBefore, Instant pendingBefore, String reason) {
        return count(jdbc.query(EXPIRE_STALE_SQL, ps -> {
            ps.setString(1, reason);
            ps.setObject(2, tenantId, Types.OTHER);
            ps.setTimestamp(3, Timestamp.from(initiatingBefore));
            ps.setTimestamp(4, Timestamp.from(pendingBefore));
        }, (rs, i) -> rs.getInt(1)));
    }

    private static int count(List<Integer> rows) {
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @Override
    public void insert(PaymentRequest r) {
        String schema = TenantSchema.validate(r.tenantSchema());
        jdbc.update(INSERT_SQL, ps -> {
            int i = 1;
            ps.setObject(i++, r.id(), Types.OTHER);
            ps.setObject(i++, r.tenantId(), Types.OTHER);
            ps.setString(i++, schema);
            ps.setObject(i++, r.requestedBy(), Types.OTHER);
            ps.setString(i++, r.provider().name());
            ps.setString(i++, r.method() == null ? null : r.method().name());
            ps.setString(i++, r.phone());
            ps.setString(i++, r.planCode());
            ps.setString(i++, r.planLabel());
            ps.setString(i++, r.cycleCode());
            ps.setString(i++, r.cycleLabel());
            if (r.pack()) {
                ps.setNull(i++, Types.INTEGER); // a pack has no plan (shape check)
            } else {
                ps.setInt(i++, r.months());
            }
            ps.setLong(i++, r.baseXaf());
            ps.setLong(i++, r.reductionXaf());
            ps.setLong(i++, r.amountDueXaf());
            ps.setString(i++, r.currency());
            ps.setObject(i++, r.discountId(), Types.OTHER);
            ps.setString(i++, r.discountLabel());
            ps.setString(i++, r.promoCode());
            ps.setTimestamp(i++, Timestamp.from(r.pricedAt()));
            ps.setString(i++, r.status().name());
            ps.setString(i++, r.nextStep() == null ? null : r.nextStep().name());
            ps.setString(i++, r.ussdCode());
            ps.setString(i++, r.appTransactionRef());
            ps.setString(i++, r.providerTransactionRef());
            ps.setString(i++, r.failureReason());
            ps.setObject(i++, r.tenantPaymentId(), Types.OTHER);
            ps.setDate(i++, r.periodStart() == null ? null : Date.valueOf(r.periodStart()));
            ps.setDate(i++, r.periodEnd() == null ? null : Date.valueOf(r.periodEnd()));
            ps.setTimestamp(i++, r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()));
            ps.setTimestamp(i++, r.confirmedAt() == null ? null : Timestamp.from(r.confirmedAt()));
            ps.setTimestamp(i++, Timestamp.from(r.createdAt()));
            ps.setString(i++, r.kind().name());
            ps.setLong(i++, r.creditAppliedXaf());
            ps.setString(i++, r.packCode());
            ps.setString(i++, r.packLabel());
            ps.setString(i++, r.packMetric() == null ? null : r.packMetric().name());
            if (r.pack()) {
                ps.setInt(i, r.packQuantity());
            } else {
                ps.setNull(i, Types.INTEGER);
            }
        });
    }

    @Override
    public void recordProviderAnswer(UUID id, String providerRef, String providerStatus, NextStep nextStep,
                                     String ussdCode) {
        jdbc.update(PROVIDER_ANSWER_SQL, ps -> {
            ps.setString(1, providerRef);
            ps.setString(2, providerStatus);
            ps.setString(3, nextStep.name());
            ps.setString(4, ussdCode);
            ps.setObject(5, id, Types.OTHER);
        });
    }

    @Override
    public void recordStep(UUID id, NextStep nextStep, String ussdCode, String providerStatus) {
        jdbc.update(STEP_SQL, ps -> {
            ps.setString(1, nextStep.name());
            ps.setString(2, ussdCode);
            ps.setString(3, providerStatus);
            ps.setObject(4, id, Types.OTHER);
        });
    }

    @Override
    public boolean markFailed(UUID id, Set<PaymentStatus> from, String reason, String providerStatus) {
        return count(jdbc.query(con -> {
            var ps = con.prepareStatement(FAIL_SQL);
            ps.setString(1, reason);
            ps.setString(2, providerStatus);
            ps.setObject(3, id, Types.OTHER);
            ps.setArray(4, con.createArrayOf("varchar", from.stream().map(Enum::name).sorted().toArray()));
            return ps;
        }, (rs, i) -> rs.getInt(1))) == 1;
    }

    @Override
    public boolean cancel(UUID id, UUID tenantId, String reason) {
        return count(jdbc.query(CANCEL_SQL, ps -> {
            ps.setString(1, reason);
            ps.setObject(2, id, Types.OTHER);
            ps.setObject(3, tenantId, Types.OTHER);
        }, (rs, i) -> rs.getInt(1))) == 1;
    }

    @Override
    public void markCredited(UUID id, String reason) {
        jdbc.update(CREDITED_SQL, reason, id);
    }

    @Override
    public Optional<PaymentRequest> find(UUID id) {
        return first(jdbc.query(FIND_SQL, ROW, id));
    }

    @Override
    public Optional<PaymentRequest> find(UUID id, UUID tenantId) {
        return first(jdbc.query(FIND_OF_TENANT_SQL, ROW, id, tenantId));
    }

    @Override
    public Optional<PaymentRequest> inFlight(UUID tenantId) {
        return first(jdbc.query(IN_FLIGHT_SQL, ROW, tenantId));
    }

    @Override
    public Optional<PaymentRequest> lock(UUID id) {
        return first(jdbc.query(LOCK_SQL, ROW, id));
    }

    @Override
    public Optional<PaymentRequest> findByProviderRef(PaymentProvider provider, String providerRef) {
        return first(jdbc.query(BY_PROVIDER_REF_SQL, ROW, provider.name(), providerRef));
    }

    @Override
    public Optional<PaymentRequest> findByAppRef(String appTransactionRef) {
        return first(jdbc.query(BY_APP_REF_SQL, ROW, appTransactionRef));
    }

    @Override
    public boolean attachProviderRef(UUID id, String providerRef) {
        return jdbc.update(ATTACH_REF_SQL, ps -> {
            ps.setString(1, providerRef);
            ps.setObject(2, id, Types.OTHER);
        }) == 1;
    }

    @Override
    public int countSince(UUID tenantId, Instant since) {
        Integer n = jdbc.queryForObject(COUNT_SINCE_SQL, Integer.class, tenantId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    @Override
    public boolean freeOfferTaken(UUID tenantId, UUID discountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(FREE_TAKEN_SQL, Boolean.class, tenantId, discountId));
    }

    @Override
    public void confirm(UUID id, UUID tenantPaymentId, PlanPeriod period, Instant at) {
        jdbc.update(CONFIRM_SQL, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setObject(2, tenantPaymentId, Types.OTHER);
            ps.setDate(3, period == null ? null : Date.valueOf(period.start()));
            ps.setDate(4, period == null ? null : Date.valueOf(period.end()));
            ps.setTimestamp(5, period == null ? null : Timestamp.from(period.expiresAt()));
            ps.setObject(6, id, Types.OTHER);
        });
    }

    @Override
    public void insertLedger(UUID tenantPaymentId, PaymentRequest r, PlanPeriod period, Instant paidAt) {
        String note = "Paiement en ligne" + (r.method() == null ? "" : " — " + r.method().label())
                + (r.creditAppliedXaf() > 0 ? " (crédit du compte utilisé : "
                        + com.keevo.payments.credit.application.service.AccountCreditService.xaf(r.creditAppliedXaf())
                        + ")" : "");
        jdbc.update(LEDGER_SQL, ps -> {
            ps.setObject(1, tenantPaymentId, Types.OTHER);
            ps.setObject(2, r.tenantId(), Types.OTHER);
            ps.setInt(3, Math.toIntExact(r.amountDueXaf()));
            ps.setTimestamp(4, Timestamp.from(paidAt));
            ps.setDate(5, Date.valueOf(period.start()));
            ps.setDate(6, Date.valueOf(period.end()));
            ps.setString(7, note);
            ps.setObject(8, r.requestedBy(), Types.OTHER);
            ps.setString(9, r.planCode());
            ps.setString(10, r.cycleCode());
            ps.setObject(11, r.id(), Types.OTHER);
        });
    }

    private static final RowMapper<PaymentRequest> ROW = JdbcPaymentRequestStore::map;

    private static PaymentRequest map(ResultSet rs, int row) throws SQLException {
        String method = rs.getString("method");
        String step = rs.getString("next_step");
        Date start = rs.getDate("period_start");
        Date end = rs.getDate("period_end");
        String packMetric = rs.getString("pack_metric");
        return new PaymentRequest(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("tenant_schema"),
                rs.getObject("requested_by", UUID.class), PaymentProvider.valueOf(rs.getString("provider")),
                method == null ? null : MobileMoneyMethod.valueOf(method), rs.getString("phone"),
                rs.getString("plan_code"), rs.getString("plan_label"), rs.getString("cycle_code"),
                rs.getString("cycle_label"), rs.getInt("months"), rs.getLong("base_xaf"),
                rs.getLong("reduction_xaf"), rs.getLong("amount_due_xaf"), rs.getString("currency"),
                rs.getObject("discount_id", UUID.class), rs.getString("discount_label"), rs.getString("promo_code"),
                instant(rs, "priced_at"), PaymentStatus.valueOf(rs.getString("status")),
                step == null ? null : NextStep.valueOf(step), rs.getString("ussd_code"),
                rs.getString("app_transaction_ref"), rs.getString("provider_transaction_ref"),
                rs.getString("failure_reason"), rs.getObject("tenant_payment_id", UUID.class),
                start == null ? null : start.toLocalDate(), end == null ? null : end.toLocalDate(),
                instant(rs, "expires_at"), instant(rs, "confirmed_at"), instant(rs, "created_at"),
                PaymentKind.valueOf(rs.getString("kind")), rs.getLong("credit_applied_xaf"), rs.getString("pack_code"),
                rs.getString("pack_label"), packMetric == null ? null : UsageMetric.valueOf(packMetric),
                rs.getInt("pack_quantity"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }
}
