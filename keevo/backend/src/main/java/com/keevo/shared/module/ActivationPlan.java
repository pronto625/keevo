package com.keevo.shared.module;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link DependencyResolver#planActivation(String, String)} (Story 13.3) and of its set form
 * {@link DependencyResolver#planActivation(String, java.util.Set)} (Story 16.2, bundles): one plan over the union of
 * the targets' closures, each module once, in one topological order.
 *
 * @param targets       the modules the caller wants to activate, sorted by id, {@code core} excluded (one for a single
 *                      activation; none when only {@code core} was asked)
 * @param steps         the modules to activate, in order (dependencies first): the inactive members of the targets'
 *                      {@code requires} closures, plus the targets that are inactive. An active
 *                      target whose closure is fully active gets an empty plan; an active target with inactive hard
 *                      dependencies gets a repair plan listing only those dependencies (the target is not
 *                      re-planned)
 * @param alreadyActive the active members of {target} ∪ its {@code requires} closure, sorted by id, {@code core}
 *                      always excluded (so planning {@code core} itself gives an empty plan and an empty list)
 * @param reasons       why the plan is blocked (one {@code NOT_ENTITLED} — or {@code ELIGIBILITY_UNAVAILABLE} when
 *                      the check failed, Story 13.5 — per ineligible step, in plan order); empty when the plan can
 *                      run
 */
public record ActivationPlan(List<String> targets, List<Step> steps, List<String> alreadyActive,
                             List<BlockReason> reasons) {

    public ActivationPlan {
        targets = List.copyOf(targets);
        steps = List.copyOf(steps);
        alreadyActive = List.copyOf(alreadyActive);
        reasons = List.copyOf(reasons);
    }

    /**
     * The target of a single-module plan only ({@code null} when that target was {@code core}). Meaningless for a set
     * plan: use {@link #targets()}.
     *
     * @deprecated single-target view kept for the callers of {@link DependencyResolver#planActivation(String, String)}
     */
    @Deprecated
    public String moduleId() {
        return targets.isEmpty() ? null : targets.get(0);
    }

    /** Whether the plan cannot run as is (at least one step is not eligible). */
    public boolean blocked() {
        return !reasons.isEmpty();
    }

    /** The module ids of {@link #steps()}, in order. */
    public List<String> moduleIds() {
        return steps.stream().map(Step::moduleId).toList();
    }

    /**
     * One module to activate.
     *
     * @param moduleId the module id
     * @param eligible whether {@link ModuleActivationEligibility} allows the tenant to activate it
     */
    public record Step(String moduleId, boolean eligible) {

        public Step {
            Objects.requireNonNull(moduleId, "moduleId");
        }
    }
}
