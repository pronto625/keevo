package com.keevo.shared.infrastructure.persistence;

/**
 * FlywayTenantMigration — Stub for programmatic per-tenant Flyway migrations.
 *
 * <p>Responsible for applying SQL scripts from
 * {@code db/migration/tenant/} to each tenant schema on provisioning.
 *
 * <p>Full implementation in Story 1.2 (tenant provisioning).
 */
public class FlywayTenantMigration {

    /**
     * Apply all pending migrations to the given tenant schema.
     *
     * @param tenantId     the tenant identifier (used as schema name)
     * @param schemaName   the PostgreSQL schema to migrate
     */
    public void migrate(String tenantId, String schemaName) {
        // TODO (Story 1.2): Implement Flyway programmatic migration for tenant schema
        throw new UnsupportedOperationException(
                "FlywayTenantMigration.migrate() not yet implemented — Story 1.2");
    }
}
