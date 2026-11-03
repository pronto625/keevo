package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.util.Objects;
import java.util.Set;

/**
 * Story 16.8 — the commercial profile of a plan ({@code public.plan_profiles} + {@code public.profile_modules}):
 * its limits ({@code null} = unlimited) and its decisions on the <b>paid</b> modules. Free and core modules are
 * available to every plan and never appear here.
 *
 * <p>{@code profile_modules} records explicit decisions ({@code granted} true / false). A paid module without a
 * decision follows the plan's kind (Story 19.7a): a trial or paid plan grants it, a free plan does not — so a paid
 * module shipped later is available to paying plans until a super-admin decides otherwise.
 *
 * <p>Story 19.7a: no limit lives in the code any more. A plan without a stored profile gets the limits of the free
 * plan's profile ({@link #fallbackFrom}, fail closed); the enum's built-in limits are gone.
 *
 * @param planType     the plan
 * @param kind         the plan's kind as far as undecided paid modules go ({@link #grantsByDefault(PlanKind)})
 * @param maxStores    store limit, {@code null} = unlimited
 * @param maxProducts  active-product limit, {@code null} = unlimited
 * @param maxEmployees employee limit, {@code null} = unlimited
 * @param modules      paid modules explicitly granted
 * @param withheld     paid modules explicitly not granted
 * @param fallback     the plan's own profile could not be read: these values come from another profile (free plan,
 *                     last known) or, as a last resort, carry no limit
 */
public record PlanProfile(PlanType planType, PlanKind kind, Integer maxStores, Integer maxProducts,
                          Integer maxEmployees, Set<String> modules, Set<String> withheld, boolean fallback) {

    public PlanProfile {
        Objects.requireNonNull(planType, "planType");
        Objects.requireNonNull(kind, "kind");
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        withheld = withheld == null ? Set.of() : Set.copyOf(withheld);
    }

    /** A stored profile with explicit grants only (other paid modules follow the kind's default). */
    public static PlanProfile of(PlanType planType, PlanKind kind, Integer maxStores, Integer maxProducts,
                                 Integer maxEmployees, Set<String> modules) {
        return of(planType, kind, maxStores, maxProducts, maxEmployees, modules, Set.of());
    }

    /** A stored profile with explicit grants and explicit refusals. */
    public static PlanProfile of(PlanType planType, PlanKind kind, Integer maxStores, Integer maxProducts,
                                 Integer maxEmployees, Set<String> modules, Set<String> withheld) {
        return new PlanProfile(planType, kind, maxStores, maxProducts, maxEmployees, modules, withheld, false);
    }

    /**
     * A stored profile of a well-known seeded plan (kind from the V42 seed, {@link PlanType#seedKind()}; any other
     * code is taken as free) — tests and fixtures.
     */
    public static PlanProfile of(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                 Set<String> modules) {
        return of(planType, maxStores, maxProducts, maxEmployees, modules, Set.of());
    }

    /** Same, with explicit refusals — tests and fixtures. */
    public static PlanProfile of(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                                 Set<String> modules, Set<String> withheld) {
        return of(planType, planType.seedKind().orElse(PlanKind.FREE), maxStores, maxProducts, maxEmployees,
                modules, withheld);
    }

    /**
     * The profile used for {@code planType} when its own cannot be read: the limits and module decisions of
     * {@code source} (the free plan's profile, or the last one read), its kind for undecided modules (fail closed),
     * flagged {@link #fallback()}.
     */
    public static PlanProfile fallbackFrom(PlanType planType, PlanProfile source) {
        return new PlanProfile(planType, source.kind(), source.maxStores(), source.maxProducts(),
                source.maxEmployees(), source.modules(), source.withheld(), true);
    }

    /**
     * Last resort when no profile at all could ever be read (database down since boot): no limit, undecided paid
     * modules follow the kind — a commercial gate never blocks on a database fault. Flagged {@link #fallback()}.
     */
    public static PlanProfile unavailable(PlanType planType, PlanKind kind) {
        return new PlanProfile(planType, kind, null, null, null, Set.of(), Set.of(), true);
    }

    /** Whether this profile grants the given paid module: its explicit decision, else the kind's default. */
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
        return grantsByDefault(kind);
    }

    /** Default for a paid module without an explicit decision: trial and paid plans grant it, a free plan does not. */
    public static boolean grantsByDefault(PlanKind kind) {
        return kind != null && kind != PlanKind.FREE;
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
}
