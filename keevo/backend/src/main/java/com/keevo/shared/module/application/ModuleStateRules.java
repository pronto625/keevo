package com.keevo.shared.module.application;

import com.keevo.identity.api.PlanKind;

import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The single effective-state computation of a module for a tenant (Story 13.5, spec-socle §2.2, §9.1), evaluated
 * at every call against the given instant — no dependence on a cron that would materialise {@code EXPIRED}:
 * <ol>
 *   <li>{@code core} → {@link ModuleState#ENABLED}, always;</li>
 *   <li>unknown / {@code DELETED} tenant ({@code rows} empty) → {@link ModuleState#DISABLED} (only core);</li>
 *   <li>no row → {@code free} manifest {@link ModuleState#ENABLED}; paid manifest: <b>transition rule</b> (Story
 *       13.10a, until the 16.5 backfill materialises rows) — the state of the tenant's plan ({@link #planState}):
 *       ENABLED while premium, READ_ONLY during the 7-day grace after its end, then DISABLED;</li>
 *   <li>TRIAL / ACTIVE row: {@code expires_at} null or after now → ENABLED; past it → READ_ONLY while
 *       {@code grace_until} is after now, else DISABLED;</li>
 *   <li>EXPIRED row: {@code grace_until} after now → READ_ONLY, else DISABLED;</li>
 *   <li>DISABLED / AVAILABLE row → DISABLED.</li>
 * </ol>
 * The caller only passes installed manifests: a module that is not installed is DISABLED before reaching here.
 * Boundaries are exclusive: at {@code expires_at} exactly the subscription is over, at {@code grace_until} exactly
 * the grace is over. A row always wins over the transition rule (a {@code DISABLED} row on a Premium tenant is
 * DISABLED; an {@code ACTIVE} row on a Free tenant is ENABLED).
 */
final class ModuleStateRules {

    private ModuleStateRules() {
    }

    /**
     * @param manifest an installed module
     * @param rows     the tenant's rows (module id → row), empty when the tenant is unknown
     * @param plan     the tenant's subscription plan ({@link Plan#NONE} when it has none)
     * @param now      the current instant (from the injected {@code Clock})
     */
    static ModuleState stateOf(ModuleManifest manifest, Optional<Map<String, Row>> rows, Plan plan, Instant now) {
        return stateOf(manifest, rows, plan, null, now);
    }

    /**
     * Story 16.8 — same, a paid module without a row following the plan type's profile ({@code null} → built-in
     * defaults: premium grants every paid module, {@code FREE} none).
     */
    static ModuleState stateOf(ModuleManifest manifest, Optional<Map<String, Row>> rows, Plan plan,
                               PlanProfile profile, Instant now) {
        if (manifest.isCore()) {
            return ModuleState.ENABLED;
        }
        if (rows.isEmpty()) {
            return ModuleState.DISABLED;
        }
        Row row = rows.get().get(manifest.id());
        if (row == null) {
            return manifest.free() ? ModuleState.ENABLED
                    : planState(plan, PlanModuleRows.granted(plan, profile, manifest.id()), now);
        }
        return switch (row.status()) {
            case TRIAL, ACTIVE -> row.expiresAt() == null || row.expiresAt().isAfter(now)
                    ? ModuleState.ENABLED : grace(row, now);
            case EXPIRED -> grace(row, now);
            case DISABLED, AVAILABLE -> ModuleState.DISABLED;
        };
    }

    /** Read-only grace after the end of a plan (spec-socle §6.4, plan-refonte §6.4: « grâce 7 j »). */
    static final Duration PLAN_GRACE = Duration.ofDays(7);

    /**
     * Transition rule of paid modules without a row (Story 13.10a, plan-refonte-modulaire §6.1, §6.3, §6.4) — same
     * outcome as a backfilled row ({@code expires_at} = plan end, {@code grace_until} = end + 7 days):
     * <ul>
     *   <li>plan unreadable → ENABLED (commercial gate: never block a paying customer on a database fault);</li>
     *   <li>{@code PREMIUM} / {@code PREMIUM_TRIAL}, subscription {@code ACTIVE}, {@code expires_at} null or after
     *       now → ENABLED;</li>
     *   <li>{@code PREMIUM} / {@code PREMIUM_TRIAL} {@code ACTIVE} or {@code EXPIRED} past {@code expires_at} →
     *       READ_ONLY until {@code expires_at} + 7 days, then DISABLED;</li>
     *   <li>{@code FREE} right after the nightly expiry downgrade (latest plan change = {@code EXPIRY}, which erases
     *       {@code expires_at}) → READ_ONLY until that downgrade + 7 days, then DISABLED;</li>
     *   <li>anything else ({@code FREE}, no subscription, {@code SUSPENDED}) → DISABLED.</li>
     * </ul>
     * Boundaries are exclusive, like module rows. Story 19.7a: {@code PREMIUM} / {@code PREMIUM_TRIAL} read « a plan of
     * kind TRIAL or PAID », {@code FREE} « a plan of kind FREE » (plan catalogue).
     */
    static ModuleState planState(Plan plan, Instant now) {
        return planState(plan, PlanModuleRows.grantedByDefault(plan), now);
    }

    /**
     * Story 16.8 — the transition rule of one paid module given whether the plan type's profile grants it: granted to
     * {@code FREE} → ENABLED; not granted to a premium plan → DISABLED; otherwise as above. Same state as the row
     * {@link PlanModuleRows#rowFor(Plan, Instant, boolean)} derives (parity).
     */
    static ModuleState planState(Plan plan, boolean granted, Instant now) {
        if (plan == null) {
            return ModuleState.DISABLED;
        }
        if (plan.unreadable()) {
            return ModuleState.ENABLED;
        }
        boolean premiumType = plan.kind() != null && plan.kind().expires();
        boolean free = plan.kind() == PlanKind.FREE;
        if (free && granted) {
            return ModuleState.ENABLED;
        }
        if (premiumType && !granted) {
            return ModuleState.DISABLED;
        }
        if (premiumType && "ACTIVE".equals(plan.status())
                && (plan.expiresAt() == null || plan.expiresAt().isAfter(now))) {
            return ModuleState.ENABLED;
        }
        if (premiumType && ("ACTIVE".equals(plan.status()) || "EXPIRED".equals(plan.status()))
                && plan.expiresAt() != null) {
            return now.isBefore(plan.expiresAt().plus(PLAN_GRACE)) ? ModuleState.READ_ONLY : ModuleState.DISABLED;
        }
        if (free && plan.expiryDowngradeAt() != null
                && now.isBefore(plan.expiryDowngradeAt().plus(PLAN_GRACE))) {
            return ModuleState.READ_ONLY;
        }
        return ModuleState.DISABLED;
    }

    private static ModuleState grace(Row row, Instant now) {
        return row.graceUntil() != null && row.graceUntil().isAfter(now) ? ModuleState.READ_ONLY : ModuleState.DISABLED;
    }
}
