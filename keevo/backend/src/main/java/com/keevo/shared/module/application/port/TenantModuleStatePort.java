package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read port of the per-tenant module state ({@code public.tenant_modules}, spec-socle §2.2), used by the
 * {@code ModuleRegistry}. Implemented by {@code shared.module.adapter.out.persistence.JdbcTenantModuleStateStore}.
 */
public interface TenantModuleStatePort {

    /**
     * Every {@code tenant_modules} row of the tenant (module id → row), in one query that resolves the tenant from
     * its schema name. Rows of modules that are not installed are included (the caller filters).
     *
     * @param tenantSchema a valid tenant schema name ({@code kv_xxxxxx})
     * @return empty when no non-{@code DELETED} {@code public.tenants} row has this schema name; otherwise the rows
     *         (possibly none)
     */
    Optional<Map<String, Row>> findRows(String tenantSchema);

    /**
     * The tenant's rows <em>and</em> its current subscription plan (Story 13.10a transition rule), read together.
     * The JDBC adapter reads the plan in its own statements, isolated from the module rows (a fault there yields
     * {@link Plan#UNREADABLE}, never an exception); the default (test doubles) answers {@link #findRows} with
     * {@link Plan#NONE} — no premium plan.
     *
     * @return empty when no non-{@code DELETED} {@code public.tenants} row has this schema name
     */
    default Optional<TenantRows> findTenantRows(String tenantSchema) {
        return findRows(tenantSchema).map(rows -> new TenantRows(rows, Plan.NONE));
    }

    /**
     * What the effective state needs about a known tenant: its {@code tenant_modules} rows and its plan.
     *
     * @param rows module id → row (possibly empty)
     * @param plan the tenant's subscription plan, {@link Plan#NONE} when it has none
     */
    record TenantRows(Map<String, Row> rows, Plan plan) {

        public TenantRows {
            rows = Map.copyOf(rows);
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * The tenant's subscription as {@code TenantPlanQuery} sees it (the oldest row of its {@code subscriptions}
     * table, Story 12.2d) — read only for the transition rule of paid modules without a row (Story 13.10a).
     *
     * @param planType          {@code FREE} | {@code PREMIUM_TRIAL} | {@code PREMIUM}; {@code null} = no subscription
     * @param status            {@code ACTIVE} | {@code SUSPENDED} | {@code EXPIRED}; {@code null} = no subscription
     * @param expiresAt         end of the plan; {@code null} = no end
     * @param expiryDowngradeAt when the nightly job downgraded an expired plan to FREE, if that is the tenant's latest
     *                          plan change ({@code public.tenant_plan_events}, cause {@code EXPIRY}); {@code null}
     *                          otherwise — anchors the read-only grace once the plan's own end date is erased
     * @param unreadable        the plan could not be read (database fault): paid modules are granted for the
     *                          request — a commercial gate never blocks a paying customer on a hiccup
     */
    record Plan(String planType, String status, Instant expiresAt, Instant expiryDowngradeAt, boolean unreadable) {

        /** No subscription row: never premium. */
        public static final Plan NONE = new Plan(null, null, null);
        /** The plan read failed (Story 13.10a review): paid modules granted, free modules unaffected. */
        public static final Plan UNREADABLE = new Plan(null, null, null, null, true);

        public Plan(String planType, String status, Instant expiresAt) {
            this(planType, status, expiresAt, null, false);
        }
    }

    /**
     * One {@code tenant_modules} row, as far as the effective state needs it (Story 13.5).
     *
     * @param status     the stored status (never null)
     * @param expiresAt  end of the trial / subscription; {@code null} = no end
     * @param graceUntil end of the read-only grace; {@code null} = no grace
     * @param tier       the stored tier ({@code FREE}|{@code STANDARD}|{@code PREMIUM}|{@code ENTERPRISE}), informative
     */
    record Row(TenantModuleStatus status, Instant expiresAt, Instant graceUntil, String tier) {

        public Row {
            Objects.requireNonNull(status, "status");
        }

        /** A row with only a status (no expiry, no grace), tier {@code FREE}. */
        public static Row of(TenantModuleStatus status) {
            return new Row(status, null, null, "FREE");
        }
    }
}
