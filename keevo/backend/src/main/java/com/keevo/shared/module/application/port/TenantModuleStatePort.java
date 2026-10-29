package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read port of the per-tenant module state ({@code public.tenant_modules}, spec-socle §2.2), used by the
 * {@code ModuleRegistry}. Implemented by {@code shared.module.adapter.out.persistence.JdbcTenantModuleStateStore}.
 */
public interface TenantModuleStatePort {

    /**
     * Every {@code tenant_modules} row of the tenant (module id → row), in one query that resolves the tenant from
     * its schema name. Rows of modules that are not installed are included (the caller filters).
     *
     * @param tenantSchema a valid tenant schema name ({@code kv_xxxxxx})
     * @return empty when no non-{@code DELETED} {@code public.tenants} row has this schema name; otherwise the rows
     *         (possibly none)
     */
    Optional<Map<String, Row>> findRows(String tenantSchema);

    /**
     * One {@code tenant_modules} row, as far as the effective state needs it (Story 13.5).
     *
     * @param status     the stored status (never null)
     * @param expiresAt  end of the trial / subscription; {@code null} = no end
     * @param graceUntil end of the read-only grace; {@code null} = no grace
     * @param tier       the stored tier ({@code FREE}|{@code STANDARD}|{@code PREMIUM}|{@code ENTERPRISE}), informative
     */
    record Row(TenantModuleStatus status, Instant expiresAt, Instant graceUntil, String tier) {

        public Row {
            Objects.requireNonNull(status, "status");
        }

        /** A row with only a status (no expiry, no grace), tier {@code FREE}. */
        public static Row of(TenantModuleStatus status) {
            return new Row(status, null, null, "FREE");
        }
    }
}
