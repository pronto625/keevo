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
 */
final class ReportingModuleGate {

    private static final Logger log = LoggerFactory.getLogger(ReportingModuleGate.class);

    private ReportingModuleGate() {
    }

    /** Whether reporting is DISABLED for the tenant ({@code false} without a registry — unit tests). */
    static boolean disabled(ModuleRegistry registry, String tenantSchema, String job) {
        if (registry == null) {
            return false;
        }
        try {
            if (registry.moduleState(tenantSchema, ReportingModule.ID) == ModuleState.DISABLED) {
                log.debug("{}: module « Rapports » disabled for tenant {} — skipped", job, tenantCode(tenantSchema));
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("{}: module state of « Rapports » unreadable for tenant {} ({}) — processed", job,
                    tenantCode(tenantSchema), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String tenantCode(String schema) {
        return schema != null && schema.startsWith("kv_") ? "KV-" + schema.substring(3).toUpperCase() : "?";
    }
}
