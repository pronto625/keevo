package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

import java.util.Objects;
import java.util.Set;

/**
 * Story 16.8 — the commercial profile of a plan type ({@code public.plan_profiles} + {@code public.profile_modules}):
 * its limits ({@code null} = unlimited) and its decisions on the <b>paid</b> modules. Free and core modules are
 * available to every plan and never appear here.
 *
 * <p>{@code profile_modules} records explicit decisions ({@code granted} true / false). A paid module without a
 * decision follows the plan type's default: {@code PREMIUM} / {@code PREMIUM_TRIAL} grant it, {@code FREE} does not —
 * so a paid module shipped later is available to Premium until a super-admin decides otherwise.
 *
 * @param planType     the plan type
 * @param maxStores    store limit, {@code null} = unlimited
 * @param maxProducts  active-product limit, {@code null} = unlimited
 * @param maxEmployees employee limit, {@code null} = unlimited
 * @param modules      paid modules explicitly granted
 * @param withheld     paid modules explicitly not granted
 * @param fallback     the profile could not be read and comes from the {@link PlanType} constants
 */
public record PlanProfile(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                          Set<String> modules, Set<String> withheld, boolean fallback) {

    public PlanProfile {
        Objects.requireNonNull(planType, "planType");
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        withheld = withheld == null ? Set.of() : Set.copyOf(withheld);
    }

    /** A stored profile with explicit grants only (other paid modules follow the plan type's default). */
    public static PlanProfile of(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                 Set<String> modules) {
        return of(planType, maxStores, maxProducts, maxEmployees, modules, Set.of());
    }

    /** A stored profile with explicit grants and explicit refusals. */
    public static PlanProfile of(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                 Set<String> modules, Set<String> withheld) {
        return new PlanProfile(planType, maxStores, maxProducts, maxEmployees, modules, withheld, false);
    }

    /**
     * The profile rebuilt from the {@link PlanType} constants (used only when {@code plan_profiles} cannot be read):
     * {@code Integer.MAX_VALUE} → unlimited; no explicit decision, so premium types grant every paid module and
     * {@code FREE} none.
     */
    public static PlanProfile fromEnum(PlanType planType) {
        return new PlanProfile(planType, finite(planType.getMaxStores()), finite(planType.getMaxProducts()),
                finite(planType.getMaxEmployees()), Set.of(), Set.of(), true);
    }

    /** Whether this profile grants the given paid module: its explicit decision, else the plan type's default. */
    public boolean grants(String moduleId) {
        if (moduleId == null) {
            return false;
        }
        if (modules.contains(moduleId)) {
            return true;
        }
        if (withheld.contains(moduleId)) {
            return false;
        }
        return grantsByDefault(planType);
    }

    /** Default for a paid module without an explicit decision: premium types grant it, {@code FREE} does not. */
    public static boolean grantsByDefault(PlanType planType) {
        return planType != PlanType.FREE;
    }

    /** Legacy {@code subscriptions.max_*} / {@code tenants.max_*} value: unlimited → {@code Integer.MAX_VALUE}. */
    public int legacyMaxStores() {
        return legacy(maxStores);
    }

    public int legacyMaxProducts() {
        return legacy(maxProducts);
    }

    public int legacyMaxEmployees() {
        return legacy(maxEmployees);
    }

    private static int legacy(Integer limit) {
        return limit != null ? limit : Integer.MAX_VALUE;
    }

    private static Integer finite(int limit) {
        return limit == Integer.MAX_VALUE ? null : limit;
    }
}
