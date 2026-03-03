package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * FlywayTenantMigration — Programmatic per-tenant Flyway migrations.
 *
 * <p>Responsible for:
 * 1. Creating the PostgreSQL schema for the tenant (CREATE SCHEMA IF NOT EXISTS)
 * 2. Applying SQL scripts from {@code db/migration/tenant/} to the new schema
 *
 * <p>Called by {@code TenantFactory} during registration.
 * Runs WITHIN the enclosing @Transactional boundary of RegistrationService.
 *
 * <p>Note: DDL statements (CREATE SCHEMA) cause an implicit commit in PostgreSQL.
 * The schema creation is done before tenant data is written, ensuring clean rollback
 * of tenant row if subsequent steps fail (AC4).
 */
@Component
public class FlywayTenantMigration {

    private static final Logger log = LoggerFactory.getLogger(FlywayTenantMigration.class);

    private final DataSource dataSource;

    public FlywayTenantMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create the tenant schema and apply all Flyway migrations to it.
     *
     * @param tenantId   tenant UUID (for logging/tracing only)
     * @param schemaName PostgreSQL schema name (e.g., "kv_abc123")
     * @throws DomainException TENANT_PROVISION_FAILED if schema creation or migration fails
     */
    public void migrate(String tenantId, String schemaName) {
        // M1: tenantId used for structured logging / tracing
        log.info("Provisioning tenant schema: tenantId={} schema={}", tenantId, schemaName);
        // 1. Create PostgreSQL schema (DDL — implicit commit in PostgreSQL)
        createSchema(schemaName);

        // 2. Run Flyway migrations on the new tenant schema
        runFlywayMigrations(schemaName);
        log.info("Tenant schema provisioned successfully: tenantId={} schema={}", tenantId, schemaName);
    }

    private void createSchema(String schemaName) {
        // Validate schema name to prevent SQL injection (must match kv_[a-z0-9]+)
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                "Invalid schema name format: " + schemaName);
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                "Failed to create schema " + schemaName + ": " + e.getMessage());
        }
    }

    private void runFlywayMigrations(String schemaName) {
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaName)
                .locations("classpath:db/tenant-migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
            flyway.migrate();
        } catch (Exception e) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                "Flyway migration failed for schema " + schemaName + ": " + e.getMessage());
        }
    }

    /**
     * Drop a tenant schema — compensating action for failed provisioning (AC4).
     *
     * <p>Called by TenantFactory when any step after schema creation fails,
     * to prevent orphaned schemas. Best-effort: logs but does not throw.
     *
     * @param schemaName PostgreSQL schema name (e.g., "kv_abc123")
     */
    public void dropSchemaIfExists(String schemaName) {
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) {
            return; // Safety: never drop a non-tenant schema
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Dropped orphaned tenant schema: {}", schemaName);
        } catch (SQLException e) {
            // M2: Log the failure — silent swallowing hides orphaned schemas in production
            log.warn("Failed to drop orphaned schema '{}' during rollback: {}", schemaName, e.getMessage());
        }
    }
}

