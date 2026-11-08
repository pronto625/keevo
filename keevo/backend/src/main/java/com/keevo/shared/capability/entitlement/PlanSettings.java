package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

import java.util.Objects;

/**
 * Story 19.7a — the one-row {@code public.plan_settings}: which plan a tenant falls back to, which plan a new
 * registration starts on and for how long.
 *
 * @param freePlan    the plan the expiry job and the super-admin downgrade move a tenant to
 * @param trialPlan   the plan a new registration starts on
 * @param trialMonths     length of that trial, in months ({@code > 0})
 * @param defaultPaidPlan Story 19.7b — the paid plan the super-admin « activate » action grants when none is chosen
 *                        ({@code plan_settings.default_paid_plan_code})
 * @param trialEnabled    Story 21.10 — whether a new registration starts on the trial plan ({@code true}) or on the
 *                        free plan with no end date ({@code plan_settings.trial_enabled})
 */
public record PlanSettings(PlanType freePlan, PlanType trialPlan, int trialMonths, PlanType defaultPaidPlan,
                           boolean trialEnabled) {

    /** Trial on (the seeded policy). */
    public PlanSettings(PlanType freePlan, PlanType trialPlan, int trialMonths, PlanType defaultPaidPlan) {
        this(freePlan, trialPlan, trialMonths, defaultPaidPlan, true);
    }

    /** Story 21.10 — the plan a new registration starts on: the trial plan, or the free plan when the trial is off. */
    public PlanType registrationPlan() {
        return trialEnabled ? trialPlan : freePlan;
    }

    /** Story 21.10 — length of the registration plan in months; {@code null} (no end) when the trial is off. */
    public Integer registrationMonths() {
        return trialEnabled ? trialMonths : null;
    }

    public PlanSettings {
        Objects.requireNonNull(freePlan, "freePlan");
        Objects.requireNonNull(trialPlan, "trialPlan");
        Objects.requireNonNull(defaultPaidPlan, "defaultPaidPlan");
        if (trialMonths <= 0) {
            throw new IllegalArgumentException("trialMonths must be positive");
        }
    }
}
