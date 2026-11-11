package com.keevo.shared.module;

/**
 * Story 19.13 — the initial module activation rules of a new tenant (ADR-05: data, editable by the super-admin):
 * by sale flow and optional sector, the modules disabled at start. Called after onboarding (listener on
 * {@code OnboardingCompletedEvent}) and by the Bref importer (24.2) after it set the {@code DOCUMENT} flow.
 * Never applied again when the sale flow changes later.
 */
public interface ModuleActivationRulesPort {

    /**
     * Reads the tenant's sale flow and sector, resolves the rule set — the (flow, sector) set if it exists, else
     * (flow, any sector); the most specific set wins, no merge — and owner-disables its modules with one {@code INSERT … SELECT … ON CONFLICT DO NOTHING}
     * (an existing owner choice is kept). Joins the caller's transaction when there is one.
     *
     * @return the number of modules disabled by this call
     * @throws IllegalArgumentException invalid schema name
     */
    int applyInitialRules(String tenantSchema);

    /**
     * Story 24.2 — owner-disables every installed, owner-toggleable, non-platform module of the tenant that is not in
     * {@code keep} — except the modules a module left enabled (kept, or not owner-toggleable) requires, transitively —
     * with one {@code INSERT … SELECT unnest … ON CONFLICT DO NOTHING} (an existing owner choice is kept). The Bref
     * importer keeps the functions Bref had; the owner may enable the others in « Mes modules ». Entitlement is not
     * touched. Joins the caller's transaction when there is one.
     *
     * @param keep module ids to leave as they are (unknown ids are ignored)
     * @return the number of modules disabled by this call
     * @throws IllegalArgumentException invalid schema name
     */
    int ownerDisableAllBut(String tenantSchema, java.util.Set<String> keep);
}
