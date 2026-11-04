package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 19.7a — the plan catalogue ({@code public.plans} + {@code public.plan_settings}): plans are data (ADR-05).
 * Every behaviour that depends on a plan reads the plan's {@linkplain PlanKind kind} here, never a code: a plan
 * created as data works everywhere without a deploy.
 *
 * <p>Implemented by the core ({@code shared.module.adapter.out.persistence.JdbcPlanCatalog}): a tiny read-mostly
 * table cached in memory, invalidated on every write through the port (and after a short TTL, for writes made by
 * another instance). Reads are lookups in that snapshot — no query per call. SQL jobs do not call it per row: they
 * join {@code public.plans} on the code.
 */
public interface PlanCatalog {

    /** The plan of this code, empty when the catalogue does not know it. */
    Optional<PlanDefinition> find(String code);

    /** Every plan (archived included), in display order. */
    List<PlanDefinition> all();

    /** The free plan, the registration trial plan and its length. */
    PlanSettings settings();

    /** The plan of this code. */
    default Optional<PlanDefinition> find(PlanType code) {
        return code == null ? Optional.empty() : find(code.code());
    }

    /**
     * The plan of this code.
     *
     * @throws UnknownPlanException the catalogue does not know it (an {@link IllegalArgumentException})
     */
    default PlanDefinition require(String code) {
        return find(code).orElseThrow(UnknownPlanException::new);
    }

    /** The plan type of a catalogue code (the former {@code PlanType.valueOf}). */
    default PlanType resolve(String code) {
        return require(code).code();
    }

    /**
     * The kind of the plan.
     *
     * @throws UnknownPlanException the catalogue does not know it
     */
    default PlanKind kind(PlanType code) {
        return find(code).map(PlanDefinition::kind).orElseThrow(UnknownPlanException::new);
    }

    /** The plan a tenant falls back to ({@code plan_settings.free_plan_code}). */
    default PlanType freePlan() {
        return settings().freePlan();
    }

    /** The plan a new registration starts on ({@code plan_settings.trial_plan_code}). */
    default PlanType trialPlan() {
        return settings().trialPlan();
    }

    /** Length of the registration trial, in months ({@code plan_settings.trial_months}). */
    default int trialMonths() {
        return settings().trialMonths();
    }

    /** Story 19.7b — the paid plan granted by default ({@code plan_settings.default_paid_plan_code}). */
    default PlanType defaultPaidPlan() {
        return settings().defaultPaidPlan();
    }

    /** The V1 compatibility code of the plan, derived from its kind ({@link PlanKind#v1Plan()}). */
    default PlanType v1Code(PlanType code) {
        return kind(code).v1Plan();
    }

    /** French name of the plan, or {@code fallback} when the catalogue does not know the code. */
    default String label(String code, String fallback) {
        return code == null ? fallback : find(code).map(PlanDefinition::label).orElse(fallback);
    }

    /** An in-memory catalogue over these plans and settings (the adapter's snapshot, test doubles). */
    static PlanCatalog of(List<PlanDefinition> plans, PlanSettings settings) {
        Map<String, PlanDefinition> byCode = new LinkedHashMap<>();
        plans.stream()
                .sorted(Comparator.comparingInt(PlanDefinition::sortOrder).thenComparing(p -> p.code().code()))
                .forEach(p -> byCode.put(p.code().code(), p));
        List<PlanDefinition> ordered = List.copyOf(byCode.values());
        return new PlanCatalog() {
            @Override
            public Optional<PlanDefinition> find(String code) {
                return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code));
            }

            @Override
            public List<PlanDefinition> all() {
                return ordered;
            }

            @Override
            public PlanSettings settings() {
                return settings;
            }
        };
    }

    /**
     * The V42 + V43 seed (Story 19.7b, merged offer): FREE / PRO / TEAM / BUSINESS / BUSINESS_TRIAL, the Keevo plans
     * PREMIUM_TRIAL and PREMIUM archived and hidden; trial BUSINESS_TRIAL for 1 month, default paid plan TEAM. The
     * adapter's built-in fallback when the catalogue cannot be read, and the default of test doubles. Same values as
     * the seeded rows (archive instant aside), so behaviour is identical whichever answers.
     */
    PlanCatalog SEED = of(List.of(
                    new PlanDefinition(PlanType.FREE, "Gratuit", null, 0, true, PlanKind.FREE, null),
                    new PlanDefinition(PlanType.of("PRO"), "Pro", null, 10, true, PlanKind.PAID, null),
                    new PlanDefinition(PlanType.of("TEAM"), "Team", null, 20, true, PlanKind.PAID, null),
                    new PlanDefinition(PlanType.of("BUSINESS"), "Business", null, 30, true, PlanKind.PAID, null),
                    new PlanDefinition(PlanType.of("BUSINESS_TRIAL"), "Essai Business", null, 40, true,
                            PlanKind.TRIAL, null),
                    new PlanDefinition(PlanType.PREMIUM_TRIAL, "Essai Premium", null, 90, false, PlanKind.TRIAL,
                            SeedHolder.V43_ARCHIVED),
                    new PlanDefinition(PlanType.PREMIUM, "Premium", null, 91, false, PlanKind.PAID,
                            SeedHolder.V43_ARCHIVED)),
            new PlanSettings(PlanType.FREE, PlanType.of("BUSINESS_TRIAL"), 1, PlanType.of("TEAM")));

    /** Constants of the built-in seed. */
    final class SeedHolder {
        /** When V43 archived the Keevo plans (the fallback only needs « archived »). */
        static final java.time.Instant V43_ARCHIVED = java.time.Instant.parse("2026-11-04T00:00:00Z");

        private SeedHolder() {
        }
    }
}
