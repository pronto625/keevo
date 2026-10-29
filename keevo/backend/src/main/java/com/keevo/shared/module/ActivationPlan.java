package com.keevo.shared.module;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link DependencyResolver#planActivation(String, String)} (Story 13.3).
 *
 * @param moduleId      the module the caller wants to activate
 * @param steps         the modules to activate, in order (dependencies first, target last): the inactive members
 *                      of the target's {@code requires} closure, plus the target when it is inactive. An active
 *                      target whose closure is fully active gets an empty plan; an active target with inactive hard
 *                      dependencies gets a repair plan listing only those dependencies (the target is not
 *                      re-planned)
 * @param alreadyActive the active members of {target} ∪ its {@code requires} closure, sorted by id, {@code core}
 *                      always excluded (so planning {@code core} itself gives an empty plan and an empty list)
 * @param reasons       why the plan is blocked (one {@code NOT_ENTITLED} per ineligible step, in plan order); empty
 *                      when the plan can run
 */
public record ActivationPlan(String moduleId, List<Step> steps, List<String> alreadyActive,
                             List<BlockReason> reasons) {

    public ActivationPlan {
        Objects.requireNonNull(moduleId, "moduleId");
        steps = List.copyOf(steps);
        alreadyActive = List.copyOf(alreadyActive);
        reasons = List.copyOf(reasons);
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
