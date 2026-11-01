package com.keevo.shared.module;

/**
 * Decides whether a module can be activated or deactivated for a tenant without breaking its hard dependencies
 * (Story 13.3, spec-socle §5.2, MOD-FR4/5/6). Read-only: it computes a plan or a check from the installed manifests
 * and the tenant's effective active set ({@link ModuleRegistry#activeModules(String)}); activation itself is Story
 * 13.8.
 *
 * <p>Only {@code requires} creates constraints; {@code enhances} and {@code needs} never do. Outputs are
 * deterministic (ties broken by module id). An unknown module id, an invalid tenant schema or the platform-admin
 * schema ({@code public}, which has no tenant modules) throw {@link IllegalArgumentException}.
 */
public interface DependencyResolver {

    /**
     * The ordered activation of {@code moduleId} for the tenant: its missing hard dependencies first (transitive,
     * topological order), the target last. Modules already active are listed in {@link ActivationPlan#alreadyActive()};
     * {@code core} never appears (always active). Each step is checked through {@link ModuleActivationEligibility};
     * the plan is blocked when any step is not eligible.
     */
    ActivationPlan planActivation(String tenantSchema, String moduleId);

    /**
     * Story 16.2 (bundles, MOD-FR12) — one activation plan for several targets: the union of their {@code requires}
     * closures, each module once, in one topological order (dependencies first, ties broken by id), with the same
     * rules as the single form (which is the one-element case). {@code core} targets are ignored. An empty set, a
     * {@code null} or unknown id throw {@link IllegalArgumentException}.
     */
    ActivationPlan planActivation(String tenantSchema, java.util.Set<String> moduleIds);

    /**
     * Whether {@code moduleId} can be deactivated for the tenant: blocked when an active module requires it
     * (directly or transitively, through {@code requires} only), or when it is {@code core}.
     */
    DeactivationCheck checkDeactivation(String tenantSchema, String moduleId);
}
