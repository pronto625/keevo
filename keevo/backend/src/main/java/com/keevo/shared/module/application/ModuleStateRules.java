package com.keevo.shared.module.application;

import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The single effective-state computation of a module for a tenant (Story 13.5, spec-socle §2.2, §9.1), evaluated
 * at every call against the given instant — no dependence on a cron that would materialise {@code EXPIRED}:
 * <ol>
 *   <li>{@code core} → {@link ModuleState#ENABLED}, always;</li>
 *   <li>unknown / {@code DELETED} tenant ({@code rows} empty) → {@link ModuleState#DISABLED} (only core);</li>
 *   <li>no row → {@code free} manifest {@link ModuleState#ENABLED}, paid {@link ModuleState#DISABLED};</li>
 *   <li>TRIAL / ACTIVE row: {@code expires_at} null or after now → ENABLED; past it → READ_ONLY while
 *       {@code grace_until} is after now, else DISABLED;</li>
 *   <li>EXPIRED row: {@code grace_until} after now → READ_ONLY, else DISABLED;</li>
 *   <li>DISABLED / AVAILABLE row → DISABLED.</li>
 * </ol>
 * The caller only passes installed manifests: a module that is not installed is DISABLED before reaching here.
 * Boundaries are exclusive: at {@code expires_at} exactly the subscription is over, at {@code grace_until} exactly
 * the grace is over.
 */
final class ModuleStateRules {

    private ModuleStateRules() {
    }

    /**
     * @param manifest an installed module
     * @param rows     the tenant's rows (module id → row), empty when the tenant is unknown
     * @param now      the current instant (from the injected {@code Clock})
     */
    static ModuleState stateOf(ModuleManifest manifest, Optional<Map<String, Row>> rows, Instant now) {
        if (manifest.isCore()) {
            return ModuleState.ENABLED;
        }
        if (rows.isEmpty()) {
            return ModuleState.DISABLED;
        }
        Row row = rows.get().get(manifest.id());
        if (row == null) {
            return manifest.free() ? ModuleState.ENABLED : ModuleState.DISABLED;
        }
        return switch (row.status()) {
            case TRIAL, ACTIVE -> row.expiresAt() == null || row.expiresAt().isAfter(now)
                    ? ModuleState.ENABLED : grace(row, now);
            case EXPIRED -> grace(row, now);
            case DISABLED, AVAILABLE -> ModuleState.DISABLED;
        };
    }

    private static ModuleState grace(Row row, Instant now) {
        return row.graceUntil() != null && row.graceUntil().isAfter(now) ? ModuleState.READ_ONLY : ModuleState.DISABLED;
    }
}
