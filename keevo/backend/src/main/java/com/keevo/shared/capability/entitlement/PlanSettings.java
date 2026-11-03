package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanType;

import java.util.Objects;

/**
 * Story 19.7a — the one-row {@code public.plan_settings}: which plan a tenant falls back to, which plan a new
 * registration starts on and for how long.
 *
 * @param freePlan    the plan the expiry job and the super-admin downgrade move a tenant to
 * @param trialPlan   the plan a new registration starts on
 * @param trialMonths length of that trial, in months ({@code > 0})
 */
public record PlanSettings(PlanType freePlan, PlanType trialPlan, int trialMonths) {

    public PlanSettings {
        Objects.requireNonNull(freePlan, "freePlan");
        Objects.requireNonNull(trialPlan, "trialPlan");
        if (trialMonths <= 0) {
            throw new IllegalArgumentException("trialMonths must be positive");
        }
    }
}
