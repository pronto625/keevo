package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Write side of {@code public.tenant_modules} used by the module lifecycle (Story 13.8a), plus the lookups it needs
 * (the tenant targeted by the super-admin API, the full stored rows). Implemented by
 * {@code shared.module.adapter.out.persistence.JdbcTenantModuleLifecycleStore}. Rows are only ever inserted or
 * updated — never deleted (a free module without a row is active, Story 13.2).
 */
public interface TenantModuleLifecyclePort {

    /**
     * The live tenant with this id ({@code public.tenants.status <> 'DELETED'}, not the platform tenant, valid
     * schema name); empty otherwise.
     */
    Optional<TenantRef> findTenant(UUID tenantId);

    /**
     * Every stored {@code tenant_modules} row of the live tenant with this schema (module id → row); empty map when
     * it has none or when no live tenant has this schema.
     */
    Map<String, StoredRow> findStoredRows(String tenantSchema);

    /**
     * Inserts or updates the (tenant, module) row as activated: {@code status}, {@code tier}, {@code expires_at},
     * {@code grace_until} written, {@code disabled_at} cleared; {@code provisioned_version} untouched;
     * {@code activated_at} = {@code activatedAt}, except that an existing non-null value is kept when
     * {@code keepActivatedAt} (the module was already active).
     *
     * @return the row id
     */
    UUID upsertActivated(UUID tenantId, String moduleId, TenantModuleStatus status, String tier, Instant activatedAt,
                         boolean keepActivatedAt, Instant expiresAt, Instant graceUntil);

    /**
     * Inserts or updates the (tenant, module) row as {@code DISABLED} with {@code disabled_at}; every other column of
     * an existing row is kept ({@code tier} is only used for an insert).
     *
     * @return the row id
     */
    UUID upsertDisabled(UUID tenantId, String moduleId, String tier, Instant disabledAt);

    /**
     * A tenant targeted by the lifecycle.
     *
     * @param id           {@code public.tenants.id}
     * @param code         public code ({@code KV-XXXXXX}) — the only identity used in logs and audit details
     * @param tenantSchema validated schema name
     */
    record TenantRef(UUID id, String code, String tenantSchema) {

        public TenantRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tenantSchema, "tenantSchema");
        }
    }

    /** A full {@code tenant_modules} row. */
    record StoredRow(UUID id, TenantModuleStatus status, String tier, Instant activatedAt, Instant expiresAt,
                     Instant graceUntil, Instant disabledAt, String provisionedVersion) {

        public StoredRow {
            Objects.requireNonNull(status, "status");
        }
    }
}
