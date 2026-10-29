package com.keevo.sync.sync.application.service;

import com.keevo.shared.module.ModuleState;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Story 13.5 — builds the {@code ModuleRegistry.moduleStates} answer of the sync tests. */
final class ModuleStatesFixture {

    private ModuleStatesFixture() {
    }

    /** Every listed module ENABLED (absent modules are DISABLED for the guard). */
    static Map<String, ModuleState> enabled(Set<String> moduleIds) {
        Map<String, ModuleState> states = new TreeMap<>();
        moduleIds.forEach(id -> states.put(id, ModuleState.ENABLED));
        return states;
    }
}
