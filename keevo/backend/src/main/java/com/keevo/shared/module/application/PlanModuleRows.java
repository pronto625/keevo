package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Story 16.5 — the {@code tenant_modules} rows of the paid modules equivalent to the tenant's plan: written as
 * {@code origin = 'PLAN'} rows, they give, at every instant, the same effective state as the transition rule
 * ({@link ModuleStateRules#planState}) they replace — {@code stateOf(row) == planState(plan)}. Pure: no I/O.
 *
 * <ul>
 *   <li>{@code PREMIUM_TRIAL} / {@code PREMIUM}, status {@code ACTIVE}, end null or after now → {@code TRIAL} /
 *       {@code ACTIVE}, tier {@code PREMIUM}, {@code expires_at} = plan end, {@code grace_until} = end + 7 days
 *       (null without end);</li>
 *   <li>{@code PREMIUM_TRIAL} / {@code PREMIUM}, {@code ACTIVE} or {@code EXPIRED}, with an end not covered above →
 *       {@code EXPIRED}, tier {@code PREMIUM}, {@code expires_at} = end, {@code grace_until} = end + 7 days;</li>
 *   <li>{@code FREE} whose latest plan change is the nightly {@code EXPIRY} downgrade → {@code EXPIRED}, tier
 *       {@code PREMIUM}, {@code expires_at} = the downgrade instant, {@code grace_until} = + 7 days;</li>
 *   <li>Story 16.1: either {@code EXPIRED} case whose grace is over ({@code grace_until <= now}) → {@code DISABLED},
 *       same tier and dates (what the nightly materialisation writes);</li>
 *   <li>anything else ({@code FREE}, no subscription, {@code SUSPENDED}, unknown plan) → {@code DISABLED}, tier
 *       {@code FREE};</li>
 *   <li>plan unreadable → no rows can be derived ({@link IllegalArgumentException}): the caller skips the tenant.</li>
 * </ul>
 * Paid modules: installed, not {@code free}, not core, not {@code platform}.
 */
public final class PlanModuleRows {

    public static final String TIER_PREMIUM = "PREMIUM";
    public static final String TIER_FREE = "FREE";

    private PlanModuleRows() {
    }

    /**
     * One desired row ({@code disabled_at} is set by the writer, at the instant the row turns {@code DISABLED}).
     *
     * @param status     stored status
     * @param tier       stored tier
     * @param expiresAt  end of the trial / subscription ({@code null} = none)
     * @param graceUntil end of the read-only grace ({@code null} = none)
     */
    public record Desired(TenantModuleStatus status, String tier, Instant expiresAt, Instant graceUntil) {

        public Desired {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(tier, "tier");
        }

        /** Whether a stored row carries exactly this content (same status, tier and dates). */
        public boolean sameAs(TenantModuleStatus storedStatus, String storedTier, Instant storedExpiresAt,
                              Instant storedGraceUntil) {
            return status == storedStatus && tier.equals(storedTier) && Objects.equals(expiresAt, storedExpiresAt)
                    && Objects.equals(graceUntil, storedGraceUntil);
        }
    }

    /** Whether the module's state comes from the plan (a paid tenant module). */
    public static boolean planDerived(ModuleManifest module) {
        return !module.free() && !module.isCore() && module.category() != ModuleCategory.PLATFORM;
    }

    /**
     * The row every paid module gets for this plan at {@code now}.
     *
     * @throws IllegalArgumentException the plan is {@linkplain Plan#unreadable() unreadable}
     */
    public static Desired rowFor(Plan plan, Instant now) {
        Objects.requireNonNull(now, "now");
        if (plan == null) {
            return disabled();
        }
        if (plan.unreadable()) {
            throw new IllegalArgumentException("The plan is unreadable: no row can be derived from it");
        }
        boolean premiumType = "PREMIUM".equals(plan.planType()) || "PREMIUM_TRIAL".equals(plan.planType());
        Instant end = plan.expiresAt();
        if (premiumType && "ACTIVE".equals(plan.status()) && (end == null || end.isAfter(now))) {
            TenantModuleStatus status = "PREMIUM_TRIAL".equals(plan.planType())
                    ? TenantModuleStatus.TRIAL : TenantModuleStatus.ACTIVE;
            return new Desired(status, TIER_PREMIUM, end, end != null ? end.plus(ModuleStateRules.PLAN_GRACE) : null);
        }
        if (premiumType && ("ACTIVE".equals(plan.status()) || "EXPIRED".equals(plan.status())) && end != null) {
            return expired(end, now);
        }
        if ("FREE".equals(plan.planType()) && plan.expiryDowngradeAt() != null) {
            return expired(plan.expiryDowngradeAt(), now);
        }
        return disabled();
    }

    /**
     * An ended paid row: {@code EXPIRED} while its grace runs, {@code DISABLED} with the same dates once it is over
     * (Story 16.1 — the nightly {@code ModuleExpiryMaterializer} writes exactly this row, so the plan sync and the
     * job never flip it back and forth). Exclusive bound, like {@link ModuleStateRules}.
     */
    private static Desired expired(Instant end, Instant now) {
        Instant grace = end.plus(ModuleStateRules.PLAN_GRACE);
        TenantModuleStatus status = grace.isAfter(now) ? TenantModuleStatus.EXPIRED : TenantModuleStatus.DISABLED;
        return new Desired(status, TIER_PREMIUM, end, grace);
    }

    /**
     * The desired row of every installed paid module (module id → row, catalogue order).
     *
     * @throws IllegalArgumentException the plan is unreadable
     */
    public static Map<String, Desired> compute(Plan plan, Instant now, Collection<ModuleManifest> installed) {
        Desired row = rowFor(plan, now);
        Map<String, Desired> rows = new LinkedHashMap<>();
        for (ModuleManifest module : installed) {
            if (planDerived(module)) {
                rows.put(module.id(), row);
            }
        }
        return rows;
    }

    private static Desired disabled() {
        return new Desired(TenantModuleStatus.DISABLED, TIER_FREE, null, null);
    }
}
