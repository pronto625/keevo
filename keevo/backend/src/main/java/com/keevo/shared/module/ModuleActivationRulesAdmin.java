package com.keevo.shared.module;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.13 — super-admin editing of the initial module activation rules ({@link ModuleActivationRulesPort}). A
 * rule set is keyed by sale flow ({@code POS} | {@code DOCUMENT}) and optional sector; the two sets without sector are
 * the defaults (never deleted). Resolution for a tenant: the most specific set wins, with no merge — a sector set
 * <em>replaces</em> its flow's default set (it must list every module to disable). Validation errors answer {@code MODULE_ACTIVATION_RULE_INVALID} (422) with a French
 * reason; existing tenants are never touched.
 */
public interface ModuleActivationRulesAdmin {

    /**
     * Every rule set, ordered by sale flow ({@code POS} then {@code DOCUMENT}), then by sector within a flow (the
     * flow's default set, without sector, first).
     */
    List<RuleSetView> list();

    /**
     * Creates or replaces the rule set of ({@code saleFlow}, {@code sectorType}) with exactly {@code disabledModules}.
     * Rules: known flow; known sector or none; installed modules the owner may toggle, no duplicates; the list is
     * dependency-closed (every in-catalogue module requiring a listed module is listed too).
     */
    SaveResult save(String saleFlow, String sectorType, List<String> disabledModules, UUID actorId);

    /**
     * Deletes the rule set of a sector: {@code MODULE_ACTIVATION_RULE_INVALID} (422) for an unknown flow / sector,
     * {@code INVALID_STATE} (409) for a default set (no sector), {@code NOT_FOUND} (404) when absent.
     */
    void delete(String saleFlow, String sectorType, UUID actorId);

    /**
     * One rule set.
     *
     * @param saleFlow        {@code POS} | {@code DOCUMENT}
     * @param sectorType      sector code, {@code null} for the default set of the flow
     * @param disabledModules the modules disabled at start, with their display names (sorted by id)
     * @param defaultSet      whether it is the default set of the flow (not deletable)
     * @param updatedAt       last change
     */
    record RuleSetView(String saleFlow, String sectorType, List<ModuleLifecycle.ModuleRef> disabledModules,
                       boolean defaultSet, Instant updatedAt) {

        public RuleSetView {
            disabledModules = List.copyOf(disabledModules);
        }
    }

    /** The saved set and whether it was created. */
    record SaveResult(RuleSetView ruleSet, boolean created) {
    }
}
