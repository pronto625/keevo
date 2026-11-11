package com.keevo.payments.legacyimport.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedExternalSubscription;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedPayment;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedPaymentRequest;
import com.keevo.payments.api.LegacyMonetisationImportPort.ImportedRevenueCatEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.5 — the inserts of the Bref monetisation import: one statement per table, rows passed as one JSON array
 * ({@code jsonb_to_recordset}), {@code ON CONFLICT DO NOTHING} (an id already there, or a unique provider reference /
 * App Store original transaction / store transaction held elsewhere), {@code RETURNING} the ids inserted. Joins the
 * caller's transaction. Platform tables only.
 */
@Repository
public class JdbcLegacyMonetisationWriter {

    static final String REQUESTS_SQL = """
            INSERT INTO public.payment_requests (id, tenant_id, tenant_schema, requested_by, provider, method, phone,
                plan_code, cycle_code, plan_label, cycle_label, months, base_xaf, reduction_xaf, amount_due_xaf,
                currency, priced_at, status, next_step, app_transaction_ref, provider_transaction_ref, provider_status,
                failure_reason, tenant_payment_id, period_start, period_end, expires_at, confirmed_at, created_at,
                updated_at, kind, credit_applied_xaf)
            SELECT x.id, x.tenant_id, x.tenant_schema, x.requested_by, x.provider, x.method, NULL, x.plan_code,
                x.cycle_code, x.plan_label, x.cycle_label, x.months, x.base_xaf, x.reduction_xaf, x.amount_due_xaf,
                'XAF', x.priced_at, x.status, 'NONE', x.app_transaction_ref, x.provider_transaction_ref,
                x.provider_status, x.failure_reason, x.tenant_payment_id, x.period_start, x.period_end, x.expires_at,
                x.confirmed_at, x.created_at, x.updated_at, 'PLAN', x.credit_applied_xaf
            FROM jsonb_to_recordset(?::jsonb) AS x(id uuid, tenant_id uuid, tenant_schema text, requested_by uuid,
                provider text, method text, plan_code text, cycle_code text, plan_label text, cycle_label text,
                months int, base_xaf bigint, reduction_xaf bigint, amount_due_xaf bigint, priced_at timestamptz,
                status text, app_transaction_ref text, provider_transaction_ref text, provider_status text,
                failure_reason text, tenant_payment_id uuid, period_start date, period_end date,
                expires_at timestamptz, confirmed_at timestamptz, created_at timestamptz, updated_at timestamptz,
                credit_applied_xaf bigint)
            ON CONFLICT DO NOTHING
            RETURNING id""";

    static final String LEDGER_SQL = """
            INSERT INTO public.tenant_payments (id, tenant_id, amount_xaf, credit_xaf, method, paid_at, period_start,
                period_end, note, recorded_by, plan_code, cycle_code, payment_request_id, store_transaction_id)
            SELECT x.id, x.tenant_id, x.amount_xaf, x.credit_xaf, x.method, x.paid_at, x.period_start, x.period_end,
                x.note, x.recorded_by, x.plan_code, x.cycle_code, x.payment_request_id, x.store_transaction_id
            FROM jsonb_to_recordset(?::jsonb) AS x(id uuid, tenant_id uuid, amount_xaf int, credit_xaf bigint,
                method text, paid_at timestamptz, period_start date, period_end date, note text, recorded_by uuid,
                plan_code text, cycle_code text, payment_request_id uuid, store_transaction_id text)
            ON CONFLICT DO NOTHING
            RETURNING id""";

    static final String EXTERNAL_SQL = """
            INSERT INTO public.external_subscriptions (id, tenant_id, tenant_schema, store, app_user_id,
                original_transaction_id, product_id, plan_code, cycle_code, status, auto_renew, environment,
                expires_at, grace_expires_at, granted_expires_at, last_event_ms, created_at, updated_at)
            SELECT x.id, x.tenant_id, x.tenant_schema, 'APP_STORE', x.app_user_id, x.original_transaction_id,
                x.product_id, x.plan_code, x.cycle_code, x.status, x.auto_renew, x.environment, x.expires_at,
                x.grace_expires_at, x.granted_expires_at, x.last_event_ms, x.created_at, x.updated_at
            FROM jsonb_to_recordset(?::jsonb) AS x(id uuid, tenant_id uuid, tenant_schema text, app_user_id uuid,
                original_transaction_id text, product_id text, plan_code text, cycle_code text, status text,
                auto_renew boolean, environment text, expires_at timestamptz, grace_expires_at timestamptz,
                granted_expires_at timestamptz, last_event_ms bigint, created_at timestamptz, updated_at timestamptz)
            ON CONFLICT DO NOTHING
            RETURNING id""";

    static final String EVENTS_SQL = """
            INSERT INTO public.revenuecat_webhook_event (id, type, app_user_id, original_transaction_id, environment,
                event_timestamp_ms, tenant_id, outcome, processed_at)
            SELECT x.id, x.type, x.app_user_id, x.original_transaction_id, NULL, x.event_timestamp_ms, x.tenant_id,
                'APPLIED', x.processed_at
            FROM jsonb_to_recordset(?::jsonb) AS x(id text, type text, app_user_id text,
                original_transaction_id text, event_timestamp_ms bigint, tenant_id uuid, processed_at timestamptz)
            ON CONFLICT (id) DO NOTHING
            RETURNING id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLegacyMonetisationWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Set<UUID> insertRequests(List<ImportedPaymentRequest> requests) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImportedPaymentRequest r : requests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("tenant_id", r.tenantId());
            m.put("tenant_schema", TenantSchema.validate(r.tenantSchema()));
            m.put("requested_by", r.requestedBy());
            m.put("provider", r.provider());
            m.put("method", r.method());
            m.put("plan_code", r.planCode());
            m.put("cycle_code", r.cycleCode());
            m.put("plan_label", r.planLabel());
            m.put("cycle_label", r.cycleLabel());
            m.put("months", r.months());
            m.put("base_xaf", r.baseXaf());
            m.put("reduction_xaf", r.reductionXaf());
            m.put("amount_due_xaf", r.amountDueXaf());
            m.put("priced_at", r.pricedAt());
            m.put("status", r.status());
            m.put("app_transaction_ref", r.appTransactionRef());
            m.put("provider_transaction_ref", r.providerTransactionRef());
            m.put("provider_status", r.providerStatus());
            m.put("failure_reason", r.failureReason());
            m.put("tenant_payment_id", r.tenantPaymentId());
            m.put("period_start", r.periodStart());
            m.put("period_end", r.periodEnd());
            m.put("expires_at", r.expiresAt());
            m.put("confirmed_at", r.confirmedAt());
            m.put("created_at", r.createdAt());
            m.put("updated_at", r.updatedAt());
            m.put("credit_applied_xaf", r.creditAppliedXaf());
            rows.add(m);
        }
        return uuids(REQUESTS_SQL, rows);
    }

    public Set<UUID> insertLedger(List<ImportedPayment> payments) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImportedPayment p : payments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("tenant_id", p.tenantId());
            m.put("amount_xaf", p.amountXaf());
            m.put("credit_xaf", p.creditXaf());
            m.put("method", p.method());
            m.put("paid_at", p.paidAt());
            m.put("period_start", p.periodStart());
            m.put("period_end", p.periodEnd());
            m.put("note", p.note());
            m.put("recorded_by", p.recordedBy());
            m.put("plan_code", p.planCode());
            m.put("cycle_code", p.cycleCode());
            m.put("payment_request_id", p.paymentRequestId());
            m.put("store_transaction_id", p.storeTransactionId());
            rows.add(m);
        }
        return uuids(LEDGER_SQL, rows);
    }

    public Set<UUID> insertExternalSubscriptions(List<ImportedExternalSubscription> subscriptions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImportedExternalSubscription s : subscriptions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("tenant_id", s.tenantId());
            m.put("tenant_schema", TenantSchema.validate(s.tenantSchema()));
            m.put("app_user_id", s.appUserId());
            m.put("original_transaction_id", s.originalTransactionId());
            m.put("product_id", s.productId());
            m.put("plan_code", s.planCode());
            m.put("cycle_code", s.cycleCode());
            m.put("status", s.status());
            m.put("auto_renew", s.autoRenew());
            m.put("environment", s.environment());
            m.put("expires_at", s.expiresAt());
            m.put("grace_expires_at", s.graceExpiresAt());
            m.put("granted_expires_at", s.grantedExpiresAt());
            m.put("last_event_ms", s.lastEventMs());
            m.put("created_at", s.createdAt());
            m.put("updated_at", s.updatedAt());
            rows.add(m);
        }
        return uuids(EXTERNAL_SQL, rows);
    }

    public Set<String> insertEvents(List<ImportedRevenueCatEvent> events) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImportedRevenueCatEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("app_user_id", e.appUserId());
            m.put("original_transaction_id", e.originalTransactionId());
            m.put("event_timestamp_ms", e.eventTimestampMs());
            m.put("tenant_id", e.tenantId());
            m.put("processed_at", e.processedAt());
            rows.add(m);
        }
        if (rows.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(EVENTS_SQL, String.class, json(rows)));
    }

    private Set<UUID> uuids(String sql, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(sql, UUID.class, json(rows)));
    }

    /** UUIDs, instants and dates as their ISO text (what the {@code jsonb_to_recordset} column types read). */
    private String json(List<Map<String, Object>> rows) {
        List<Map<String, Object>> plain = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(k, v == null || v instanceof Number || v instanceof Boolean ? v
                    : v.toString()));
            plain.add(m);
        }
        try {
            return mapper.writeValueAsString(plain);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("rows not serialisable", e);
        }
    }
}
