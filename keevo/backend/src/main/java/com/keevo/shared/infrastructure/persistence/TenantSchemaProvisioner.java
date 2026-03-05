package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * TenantSchemaProvisioner — Programmatic per-tenant schema creation and DDL.
 *
 * <p>Replaces Flyway SQL migration files for tenant schemas. The table structure
 * for tenant-specific data is defined directly as Java constants (text blocks)
 * here, keeping the schema definition co-located with the code that owns it.
 *
 * <p>Called by {@link com.keevo.identity.auth.application.service.TenantFactory}
 * during registration. Executes within the enclosing {@code @Transactional} boundary.
 *
 * <p>Architecture: shared infrastructure — does NOT belong to any single feature module.
 */
@Component
public class TenantSchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaProvisioner.class);

    // ── DDL — Tenant schema tables ────────────────────────────────────────────

    private static final String DDL_USERS = """
            CREATE TABLE IF NOT EXISTS users (
                id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                phone_number  VARCHAR(20)  UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                role          VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER','EMPLOYEE')),
                is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
                created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    private static final String DDL_SUBSCRIPTIONS = """
            CREATE TABLE IF NOT EXISTS subscriptions (
                id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                plan_type     VARCHAR(20) NOT NULL DEFAULT 'FREE'
                                  CHECK (plan_type IN ('FREE','PREMIUM')),
                max_stores    INT         NOT NULL DEFAULT 3,
                max_products  INT         NOT NULL DEFAULT 500,
                max_employees INT         NOT NULL DEFAULT 5,
                status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (status IN ('ACTIVE','SUSPENDED','EXPIRED')),
                created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                expires_at    TIMESTAMPTZ
            )""";

    private static final String DDL_ROLES = """
            CREATE TABLE IF NOT EXISTS roles (
                id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                name        VARCHAR(50)  UNIQUE NOT NULL,
                permissions JSONB        NOT NULL DEFAULT '{}',
                created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    private static final String DDL_USER_ROLES = """
            CREATE TABLE IF NOT EXISTS user_roles (
                id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id  UUID NOT NULL,
                role_id  UUID NOT NULL REFERENCES roles(id),
                UNIQUE(user_id, role_id)
            )""";

    private static final String DDL_STORES = """
            CREATE TABLE IF NOT EXISTS stores (
                id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                name       VARCHAR(100) NOT NULL,
                is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    private static final String DDL_CATEGORIES = """
            CREATE TABLE IF NOT EXISTS categories (
                id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                name       VARCHAR(100) NOT NULL,
                parent_id  UUID         REFERENCES categories(id) ON DELETE SET NULL,
                is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
                is_custom  BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    private static final String DDL_CATEGORIES_IDX_PARENT =
            "CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories(parent_id)";

    private static final String DDL_CATEGORIES_IDX_ACTIVE =
            "CREATE INDEX IF NOT EXISTS idx_categories_is_active ON categories(is_active)";

    private static final String DDL_TENANT_PREFERENCES = """
            CREATE TABLE IF NOT EXISTS tenant_preferences (
                id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                sector_type         VARCHAR(30),
                eod_report_time     TIME        NOT NULL DEFAULT '20:00:00',
                stock_alert_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
                created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    // ── Seed data ─────────────────────────────────────────────────────────────

    private static final String SEED_ROLES = """
            INSERT INTO roles (name, permissions) VALUES
                ('OWNER',    '{"all": true}'::jsonb),
                ('EMPLOYEE', '{"pos": true, "inventory_view": true, "stock_view": true}'::jsonb)
            ON CONFLICT (name) DO NOTHING""";

    private static final String SEED_SUBSCRIPTION = """
            INSERT INTO subscriptions (plan_type, max_stores, max_products, max_employees, status)
            VALUES ('FREE', 3, 500, 5, 'ACTIVE')""";

    private static final String SEED_STORE = """
            INSERT INTO stores (name) VALUES ('Ma Boutique')""";

    // ── DataSource ────────────────────────────────────────────────────────────

    private final DataSource dataSource;

    public TenantSchemaProvisioner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create the PostgreSQL schema for a new tenant and provision all tenant-specific tables.
     *
     * <p>Steps:
     * <ol>
     *   <li>CREATE SCHEMA IF NOT EXISTS "kv_xxxxxx"</li>
     *   <li>Create tables: users, subscriptions, roles, user_roles, stores</li>
     *   <li>Seed: default OWNER + EMPLOYEE roles, Free subscription, placeholder store</li>
     * </ol>
     *
     * @param tenantId   tenant UUID (for logging only)
     * @param schemaName PostgreSQL schema name, must match {@code ^kv_[a-z0-9]{6}$}
     * @throws DomainException TENANT_PROVISION_FAILED on any SQL error
     */
    public void provision(String tenantId, String schemaName) {
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Invalid schema name format: " + schemaName);
        }
        log.info("Provisioning tenant schema: tenantId={} schema={}", tenantId, schemaName);
        try (Connection conn = dataSource.getConnection()) {
            createSchema(conn, schemaName);
            createTables(conn, schemaName);
            seedData(conn, schemaName);
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Schema provisioning failed for " + schemaName + ": " + e.getMessage());
        }
        log.info("Tenant schema provisioned: tenantId={} schema={}", tenantId, schemaName);
    }

    /**
     * Drop a tenant schema — compensating action for failed provisioning.
     *
     * <p>Best-effort: logs but does not throw, to avoid masking the original error.
     */
    public void dropSchemaIfExists(String schemaName) {
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Dropped orphaned tenant schema: {}", schemaName);
        } catch (SQLException e) {
            log.warn("Failed to drop orphaned schema '{}': {}", schemaName, e.getMessage());
        }
    }

    // ── Private steps ─────────────────────────────────────────────────────────

    private void createSchema(Connection conn, String schemaName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        }
    }

    private void createTables(Connection conn, String schemaName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schemaName + "\"");
            stmt.execute(DDL_USERS);
            stmt.execute(DDL_SUBSCRIPTIONS);
            stmt.execute(DDL_ROLES);
            stmt.execute(DDL_USER_ROLES);
            stmt.execute(DDL_STORES);
            stmt.execute(DDL_CATEGORIES);
            stmt.execute(DDL_CATEGORIES_IDX_PARENT);
            stmt.execute(DDL_CATEGORIES_IDX_ACTIVE);
            stmt.execute(DDL_TENANT_PREFERENCES);
            stmt.execute("SET search_path TO public");
        }
    }

    private void seedData(Connection conn, String schemaName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schemaName + "\"");
            stmt.execute(SEED_ROLES);
            stmt.execute(SEED_SUBSCRIPTION);
            stmt.execute(SEED_STORE);
            stmt.execute("SET search_path TO public");
        }
    }
}
