package com.keevo.shared.module.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 19.13 — persistence of the initial module activation rules ({@code public.module_activation_rule_sets},
 * {@code public.module_activation_rule_modules}). Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcModuleActivationRuleStore}.
 */
public interface ModuleActivationRuleStore {

    /** Every rule set with its modules (two reads, no per-set query). */
    List<StoredRuleSet> findAll();

    /**
     * Creates or replaces the set of ({@code saleFlow}, {@code sectorType}) with exactly {@code modules}.
     *
     * @return {@code true} when the set was created
     */
    boolean save(String saleFlow, String sectorType, List<String> modules, Instant at);

    /** Deletes the set of ({@code saleFlow}, {@code sectorType}) and its modules; {@code false} when absent. */
    boolean delete(String saleFlow, String sectorType);

    /**
     * Owner-disables, for the live tenant with this schema, the modules of the resolved set — the
     * ({@code saleFlow}, {@code sectorType}) set if it exists, else ({@code saleFlow}, NULL) — in one
     * {@code INSERT … SELECT … ON CONFLICT DO NOTHING}.
     *
     * @return the number of rows inserted
     */
    int applyInitial(String tenantSchema, String saleFlow, String sectorType, Instant at);

    /**
     * Story 24.2 — owner-disables {@code moduleIds} for the live tenant with this schema, in one
     * {@code INSERT … SELECT unnest … ON CONFLICT DO NOTHING}.
     *
     * @return the number of rows inserted
     */
    int disable(String tenantSchema, List<String> moduleIds, Instant at);

    /**
     * One stored rule set.
     *
     * @param sectorType {@code null} for the default set of the flow
     * @param modules    module ids, sorted
     */
    record StoredRuleSet(UUID id, String saleFlow, String sectorType, List<String> modules, Instant updatedAt) {

        public StoredRuleSet {
            Objects.requireNonNull(saleFlow, "saleFlow");
            modules = List.copyOf(modules);
        }
    }
}
