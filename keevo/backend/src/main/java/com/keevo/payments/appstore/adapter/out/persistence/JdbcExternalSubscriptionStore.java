package com.keevo.payments.appstore.adapter.out.persistence;

import com.keevo.payments.appstore.domain.model.AppStoreEvent;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.model.ExternalSubscription;
import com.keevo.payments.appstore.domain.model.SubscriptionStatus;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.2 — {@link ExternalSubscriptionStore} over the public tables. Indexes used: {@code revenuecat_webhook_event}
 * primary key (claim), {@code uq_external_subscriptions_original} (routing), {@code ix_external_subscriptions_tenant}
 * (owner view), {@code ix_external_subscriptions_app_user} (TRANSFER), {@code uq_tenant_payments_store_transaction}
 * (ledger idempotence, refund void).
 */
@Repository
public class JdbcExternalSubscriptionStore implements ExternalSubscriptionStore {

    private static final String STORE = "APP_STORE";
    private static final String COLUMNS = "id, tenant_id, tenant_schema, app_user_id, original_transaction_id, "
            + "product_id, plan_code, cycle_code, status, auto_renew, environment, expires_at, grace_expires_at, "
            + "granted_expires_at, last_event_ms ";

    private static final String CLAIM = "INSERT INTO public.revenuecat_webhook_event "
            + "(id, type, app_user_id, original_transaction_id, environment, event_timestamp_ms, transaction_id, "
            + "product_id, price, currency, period_type, purchased_at, expires_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING";

    private static final String INSERT = "INSERT INTO public.external_subscriptions (" + COLUMNS + ", store) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '" + STORE + "') "
            + "ON CONFLICT (store, original_transaction_id) DO NOTHING";

    private static final String UPDATE = "UPDATE public.external_subscriptions SET tenant_id = ?, tenant_schema = ?, "
            + "app_user_id = ?, product_id = ?, plan_code = ?, cycle_code = ?, status = ?, auto_renew = ?, "
            + "environment = ?, expires_at = ?, grace_expires_at = ?, granted_expires_at = ?, last_event_ms = ?, "
            + "updated_at = now() WHERE id = ?";

    private static final String LEDGER = "INSERT INTO public.tenant_payments (id, tenant_id, amount_xaf, method, "
            + "paid_at, period_start, period_end, note, recorded_by, plan_code, cycle_code, store_transaction_id) "
            + "VALUES (gen_random_uuid(), ?, ?, 'APP_STORE', ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (store_transaction_id) WHERE store_transaction_id IS NOT NULL DO NOTHING";

    private static final String VOID = "UPDATE public.tenant_payments SET voided_at = ?, void_reason = ? "
            + "WHERE store_transaction_id = ? AND voided_at IS NULL";

    private final JdbcTemplate jdbc;

    public JdbcExternalSubscriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean eventExists(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.revenuecat_webhook_event WHERE id = ?)", Boolean.class, eventId));
    }

    @Override
    public boolean claimEvent(AppStoreEvent e) {
        return jdbc.update(CLAIM, ps -> {
            ps.setString(1, e.id());
            ps.setString(2, e.type());
            ps.setString(3, e.appUserId());
            ps.setString(4, e.originalTransactionId());
            ps.setString(5, e.environment());
            ps.setLong(6, e.eventTimestampMs());
            ps.setString(7, e.transactionId());
            ps.setString(8, e.productId());
            ps.setBigDecimal(9, storablePrice(e.priceInPurchasedCurrency()));
            ps.setString(10, e.currency());
            ps.setString(11, e.periodType());
            ps.setTimestamp(12, ts(e.purchasedAt()));
            ps.setTimestamp(13, ts(e.expiresAt()));
        }) == 1;
    }

    @Override
    public void finishEvent(String eventId, UUID tenantId, EventOutcome outcome) {
        jdbc.update("UPDATE public.revenuecat_webhook_event SET tenant_id = ?, outcome = ? WHERE id = ?", ps -> {
            ps.setObject(1, tenantId, Types.OTHER);
            ps.setString(2, outcome.name());
            ps.setString(3, eventId);
        });
    }

    @Override
    public Optional<ExternalSubscription> lock(String originalTransactionId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM public.external_subscriptions "
                        + "WHERE store = '" + STORE + "' AND original_transaction_id = ? FOR UPDATE",
                JdbcExternalSubscriptionStore::map, originalTransactionId).stream().findFirst();
    }

    @Override
    public ExternalSubscription lockOrCreate(ExternalSubscription s) {
        jdbc.update(INSERT, ps -> {
            ps.setObject(1, s.id(), Types.OTHER);
            ps.setObject(2, s.tenantId(), Types.OTHER);
            ps.setString(3, TenantSchema.validate(s.tenantSchema()));
            ps.setObject(4, s.appUserId(), Types.OTHER);
            ps.setString(5, s.originalTransactionId());
            ps.setString(6, s.productId());
            ps.setString(7, s.planCode());
            ps.setString(8, s.cycleCode());
            ps.setString(9, s.status().name());
            ps.setBoolean(10, s.autoRenew());
            ps.setString(11, s.sandbox() ? "SANDBOX" : "PRODUCTION");
            ps.setTimestamp(12, ts(s.expiresAt()));
            ps.setTimestamp(13, ts(s.graceExpiresAt()));
            ps.setTimestamp(14, ts(s.grantedExpiresAt()));
            ps.setLong(15, s.lastEventMs());
        });
        return lock(s.originalTransactionId()).orElseThrow();
    }

    @Override
    public void update(ExternalSubscription s) {
        jdbc.update(UPDATE, ps -> {
            ps.setObject(1, s.tenantId(), Types.OTHER);
            ps.setString(2, TenantSchema.validate(s.tenantSchema()));
            ps.setObject(3, s.appUserId(), Types.OTHER);
            ps.setString(4, s.productId());
            ps.setString(5, s.planCode());
            ps.setString(6, s.cycleCode());
            ps.setString(7, s.status().name());
            ps.setBoolean(8, s.autoRenew());
            ps.setString(9, s.sandbox() ? "SANDBOX" : "PRODUCTION");
            ps.setTimestamp(10, ts(s.expiresAt()));
            ps.setTimestamp(11, ts(s.graceExpiresAt()));
            ps.setTimestamp(12, ts(s.grantedExpiresAt()));
            ps.setLong(13, s.lastEventMs());
            ps.setObject(14, s.id(), Types.OTHER);
        });
    }

    @Override
    public Optional<ExternalSubscription> currentOfTenant(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM public.external_subscriptions WHERE tenant_id = ? "
                        + "ORDER BY (status IN ('ACTIVE', 'CANCELLED', 'BILLING_ISSUE')) DESC, updated_at DESC LIMIT 1",
                JdbcExternalSubscriptionStore::map, tenantId).stream().findFirst();
    }

    @Override
    public List<ExternalSubscription> lockLiveByAppUser(UUID appUserId) {
        return jdbc.query("SELECT " + COLUMNS + "FROM public.external_subscriptions WHERE app_user_id = ? "
                        + "AND status IN ('ACTIVE', 'CANCELLED', 'BILLING_ISSUE') ORDER BY id FOR UPDATE",
                JdbcExternalSubscriptionStore::map, appUserId);
    }

    @Override
    public boolean insertLedger(UUID tenantId, long amountXaf, Instant paidAt, LocalDate periodStart,
                                LocalDate periodEnd, String note, UUID recordedBy, String planCode, String cycleCode,
                                String storeTransactionId) {
        return jdbc.update(LEDGER, ps -> {
            ps.setObject(1, tenantId, Types.OTHER);
            ps.setInt(2, Math.toIntExact(amountXaf));
            ps.setTimestamp(3, Timestamp.from(paidAt));
            ps.setDate(4, Date.valueOf(periodStart));
            ps.setDate(5, Date.valueOf(periodEnd));
            ps.setString(6, note);
            ps.setObject(7, recordedBy, Types.OTHER);
            ps.setString(8, planCode);
            ps.setString(9, cycleCode);
            ps.setString(10, storeTransactionId);
        }) == 1;
    }

    @Override
    public void voidLedger(String storeTransactionId, String reason, Instant at) {
        jdbc.update(VOID, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, reason);
            ps.setString(3, storeTransactionId);
        });
    }

    @Override
    public void unvoidLedger(String storeTransactionId, String reason) {
        jdbc.update("UPDATE public.tenant_payments SET voided_at = NULL, void_reason = NULL "
                + "WHERE store_transaction_id = ? AND voided_at IS NOT NULL AND void_reason = ?", storeTransactionId,
                reason);
    }

    @Override
    public List<PendingPurchase> pendingPurchases(String originalTransactionId) {
        return jdbc.query("SELECT id, transaction_id, product_id, price, currency, period_type, purchased_at, "
                        + "expires_at FROM public.revenuecat_webhook_event WHERE original_transaction_id = ? "
                        + "AND outcome IN ('UNRESOLVED', 'UNKNOWN_PRODUCT') "
                        + "AND type IN ('INITIAL_PURCHASE', 'RENEWAL') ORDER BY event_timestamp_ms",
                (rs, i) -> new PendingPurchase(rs.getString("id"), rs.getString("transaction_id"),
                        rs.getString("product_id"), rs.getBigDecimal("price"), rs.getString("currency"),
                        rs.getString("period_type"), instant(rs, "purchased_at"), instant(rs, "expires_at")),
                originalTransactionId);
    }

    @Override
    public void markReplayed(String eventId, UUID tenantId) {
        finishEvent(eventId, tenantId, EventOutcome.APPLIED);
    }

    /** A price that fits NUMERIC(14, 2) (an absurd value is not kept rather than failing the event). */
    private static java.math.BigDecimal storablePrice(java.math.BigDecimal price) {
        return price == null || price.abs().compareTo(new java.math.BigDecimal("999999999999")) > 0 ? null
                : price.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ExternalSubscription map(ResultSet rs, int row) throws SQLException {
        return new ExternalSubscription(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_schema"), rs.getObject("app_user_id", UUID.class),
                rs.getString("original_transaction_id"), rs.getString("product_id"), rs.getString("plan_code"),
                rs.getString("cycle_code"), SubscriptionStatus.valueOf(rs.getString("status")),
                rs.getBoolean("auto_renew"), "SANDBOX".equals(rs.getString("environment")),
                instant(rs, "expires_at"), instant(rs, "grace_expires_at"), instant(rs, "granted_expires_at"),
                rs.getLong("last_event_ms"));
    }
}
