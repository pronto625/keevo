package com.keevo.reporting.report.application.service;

import com.keevo.reporting.module.ReportingModule;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Story 13.9 — per-tenant gate of the reporting schedulers (weekly reports, delivery retries): a tenant whose
 * « Rapports » module is DISABLED is skipped; READ_ONLY still runs (reports read past data). An unreadable module
 * state is logged at WARN and the tenant processed (no behaviour change when the registry is unavailable). The
 * end-of-day listener is gated by {@code @ModuleScoped} instead.
 *
 * <p>Story 13.10a: the same gate for the paid feature modules of reporting — the weekly report also needs
 * « Rapports avancés » ({@link #ADVANCED_REPORTS}).
 */
final class ReportingModuleGate {

    private static final Logger log = LoggerFactory.getLogger(ReportingModuleGate.class);

    /** Story 13.10a — paid module « Rapports avancés » (weekly report, trends, leaderboard). */
    static final String ADVANCED_REPORTS = "advanced-reports";

    private ReportingModuleGate() {
    }

    /** Whether reporting is DISABLED for the tenant ({@code false} without a registry — unit tests). */
    static boolean disabled(ModuleRegistry registry, String tenantSchema, String job) {
        return disabled(registry, tenantSchema, job, ReportingModule.ID);
    }

    /**
     * Whether any of the given modules is DISABLED for the tenant ({@code false} without a registry — unit tests);
     * an unreadable state counts as not disabled (WARN, processed).
     */
    static boolean disabled(ModuleRegistry registry, String tenantSchema, String job, String... moduleIds) {
        if (registry == null) {
            return false;
        }
        try {
            // one read for all the ids (no request cache in a scheduler thread)
            java.util.Map<String, ModuleState> states = moduleIds.length == 1
                    ? java.util.Map.of(moduleIds[0], registry.moduleState(tenantSchema, moduleIds[0]))
                    : registry.moduleStates(tenantSchema);
            for (String moduleId : moduleIds) {
                if (registry.definition(moduleId).isEmpty()) {
                    // 13.10a review: a gate on a module that is not installed is a deployment mistake — never
                    // silently stop a job for it
                    log.warn("{}: module '{}' is not installed — gate ignored, tenant {} processed", job, moduleId,
                            tenantCode(tenantSchema));
                    continue;
                }
                if (states.getOrDefault(moduleId, ModuleState.DISABLED) == ModuleState.DISABLED) {
                    log.debug("{}: module '{}' disabled for tenant {} — skipped", job, moduleId, tenantCode(tenantSchema));
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("{}: module state unreadable for tenant {} ({}) — processed", job,
                    tenantCode(tenantSchema), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String tenantCode(String schema) {
        return schema != null && schema.startsWith("kv_") ? "KV-" + schema.substring(3).toUpperCase() : "?";
    }
}
