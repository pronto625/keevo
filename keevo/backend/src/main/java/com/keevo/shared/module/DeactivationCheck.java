package com.keevo.shared.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Result of {@link DependencyResolver#checkDeactivation(String, String)} (Story 13.3). The {@link #reasons()} are
 * derived from the components, so they can never disagree with {@link #dependents()}.
 *
 * @param moduleId   the module the caller wants to deactivate
 * @param dependents the active modules that require it, directly or transitively (sorted by id, no duplicates)
 * @param coreModule whether the module is {@code core}, which can never be deactivated (then no dependents)
 */
public record DeactivationCheck(String moduleId, List<String> dependents, boolean coreModule) {

    public DeactivationCheck {
        Objects.requireNonNull(moduleId, "moduleId");
        dependents = List.copyOf(new TreeSet<>(dependents));
        if (coreModule && !dependents.isEmpty()) {
            throw new IllegalArgumentException("The core module has no dependents to report");
        }
    }

    /**
     * Why the deactivation is refused: {@code CORE_MODULE} for {@code core}, otherwise one
     * {@code REQUIRED_BY_ACTIVE_MODULE} per dependent (in {@link #dependents()} order); empty when it is allowed.
     */
    public List<BlockReason> reasons() {
        if (coreModule) {
            return List.of(new BlockReason(BlockReason.Code.CORE_MODULE, moduleId));
        }
        List<BlockReason> reasons = new ArrayList<>(dependents.size());
        for (String dependent : dependents) {
            reasons.add(new BlockReason(BlockReason.Code.REQUIRED_BY_ACTIVE_MODULE, dependent));
        }
        return List.copyOf(reasons);
    }

    /** Whether the module can be deactivated. */
    public boolean allowed() {
        return !coreModule && dependents.isEmpty();
    }

    /** Whether the deactivation is refused. */
    public boolean blocked() {
        return !allowed();
    }
}
