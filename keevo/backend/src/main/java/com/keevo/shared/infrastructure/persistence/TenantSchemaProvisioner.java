package com.keevo.shared.infrastructure.persistence;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.FoundationScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;

/**
 * TenantSchemaProvisioner — creates the schema of a new tenant at registration.
 *
 * <p>Story 16.12: the tenant tables have a single author, the V1… migrations of the foundation modules
 * ({@code db/modules/<id>/V*.sql}, {@link FoundationScripts}). Flyway runs on its own connection and cannot see a
 * schema that is not committed yet, so registration executes those same files itself, inside one transaction on its
 * connection, in module order: {@code CREATE SCHEMA}, the foundation scripts, the keyset pull indexes (best effort),
 * then the seeds (roles, trial subscription, « Ma Boutique » — registration only). Any failure rolls the whole
 * transaction back (and {@code TenantFactory} drops the schema). After the registration commits,
 * {@code ModuleProvisioner.provisionActiveModules} runs Flyway for every active module: the foundation scripts are
 * idempotent forever, so they change nothing and only their history and {@code provisioned_version} are recorded.
 *
 * <p>Called by {@link com.keevo.identity.auth.application.service.TenantFactory} during registration.
 *
 * <p>Architecture: shared infrastructure — does NOT belong to any single feature module.
 */
@Component
public class TenantSchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaProvisioner.class);

    // ── Seed data (registration only) ─────────────────────────────────────────

    private static final String SEED_ROLES = """
            INSERT INTO roles (name, permissions) VALUES
                ('OWNER',    '{"all": true}'::jsonb),
                ('EMPLOYEE', '{"pos": true, "inventory_view": true, "stock_view": true}'::jsonb)
            ON CONFLICT (name) DO NOTHING""";

    // SPEC CHANGE 2026-03-06: new tenants start on a trial. Story 19.7a: the trial plan and its length are data
    // (public.plan_settings, read through the PlanCatalog) and bound as parameters: code, then length in months.
    // WHERE NOT EXISTS ensures idempotency — provision() can be called multiple times safely.
    // Story 16.8: the legacy max_* columns are filled from the trial plan's profile (NULL = unlimited →
    // 2147483647; no profile row → unlimited). They are no longer read for decisions.
    // Without public.plan_profiles (database not migrated to V33) unlimited columns are seeded instead.
    private static final String SEED_SUBSCRIPTION_DEFAULTS = """
            INSERT INTO subscriptions (plan_type, max_stores, max_products, max_employees, status, expires_at)
            SELECT ?, 2147483647, 2147483647, 2147483647, 'ACTIVE', NOW() + make_interval(months => ?)
            WHERE NOT EXISTS (SELECT 1 FROM subscriptions)""";

    private static final String PLAN_PROFILES_PRESENT = "SELECT to_regclass('public.plan_profiles') IS NOT NULL";

    /** Parameters: trial plan code (×4), trial length in months. */
    private static final String SEED_SUBSCRIPTION = """
            INSERT INTO subscriptions (plan_type, max_stores, max_products, max_employees, status, expires_at)
            SELECT ?,
                   COALESCE((SELECT p.max_stores FROM public.plan_profiles p WHERE p.plan_type = ?), 2147483647),
                   COALESCE((SELECT p.max_products FROM public.plan_profiles p WHERE p.plan_type = ?), 2147483647),
                   COALESCE((SELECT p.max_employees FROM public.plan_profiles p WHERE p.plan_type = ?), 2147483647),
                   'ACTIVE', NOW() + make_interval(months => ?)
            WHERE NOT EXISTS (SELECT 1 FROM subscriptions)""";

    private static final String SEED_STORE = """
            INSERT INTO stores (name)
            SELECT 'Ma Boutique' WHERE NOT EXISTS (SELECT 1 FROM stores)""";

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final DataSource dataSource;
    private final FoundationScripts foundation;
    private final PlanCatalog catalog;

    /** The foundation scripts of this build ({@link FoundationScripts#fromClasspath()}), the plan catalogue. */
    @Autowired
    public TenantSchemaProvisioner(DataSource dataSource, PlanCatalog catalog) {
        this(dataSource, FoundationScripts.fromClasspath(), catalog);
    }

    /** Seeded plan catalogue — tests on a database without the public migrations. */
    public TenantSchemaProvisioner(DataSource dataSource) {
        this(dataSource, FoundationScripts.fromClasspath(), PlanCatalog.SEED);
    }

    /** Test seam: other foundation scripts (e.g. one that fails), seeded plan catalogue. */
    public TenantSchemaProvisioner(DataSource dataSource, FoundationScripts foundation) {
        this(dataSource, foundation, PlanCatalog.SEED);
    }

    public TenantSchemaProvisioner(DataSource dataSource, FoundationScripts foundation, PlanCatalog catalog) {
        this.dataSource = dataSource;
        this.foundation = foundation;
        this.catalog = catalog;
    }

    /**
     * Create the PostgreSQL schema of a new tenant with all its tables and seed data, in one transaction.
     *
     * @param tenantId   tenant UUID (for logging only)
     * @param schemaName PostgreSQL schema name, must match {@code ^kv_[a-z0-9]{6}$}
     * @throws DomainException TENANT_PROVISION_FAILED on any SQL error (nothing committed)
     */
    public void provision(String tenantId, String schemaName) {
        PlanSettings settings = catalog.settings();
        provision(tenantId, schemaName, settings.trialPlan(), settings.trialMonths());
    }

    /**
     * Same, the subscription seeded on {@code trialPlan} for {@code trialMonths} months (Story 19.7a: the caller read
     * them once from {@code public.plan_settings}, so the tenant row and the subscription agree).
     */
    public void provision(String tenantId, String schemaName, PlanType trialPlan, int trialMonths) {
        try {
            TenantSchema.validate(schemaName);
        } catch (IllegalArgumentException e) {
            // Story 12.12 (D5): report the length only, never the raw value
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED, e.getMessage());
        }
        log.info("Provisioning tenant schema: tenantId={} schema={}", tenantId, schemaName);
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                createSchema(conn, schemaName);
                createTables(conn, schemaName);
                seedData(conn, schemaName, trialPlan, trialMonths);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException | RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure); // the script failure stays the reported cause
                }
                throw e;
            } finally {
                // pooled connection: never hand it back scoped to the tenant nor outside autocommit
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO public");
                    conn.setAutoCommit(autoCommit);
                } catch (SQLException | RuntimeException restoreFailure) {
                    log.warn("Tenant schema provisioning: connection settings not restored ({}) — connection discarded",
                            restoreFailure.getClass().getSimpleName());
                    try {
                        conn.abort(Runnable::run); // never back to the pool in an unknown state
                    } catch (SQLException | RuntimeException ignored) {
                        // closing below
                    }
                }
            }
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
        if (!TenantSchema.isValid(schemaName)) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Dropped orphaned tenant schema: {}", schemaName);
        } catch (SQLException e) {
            log.warn("Failed to drop orphaned schema '{}': {}", schemaName, e.getMessage());
        }
    }

    /**
     * Drop a tenant schema for definitive account deletion (Story 14.5, FR91).
     *
     * <p><b>NOT best-effort</b> — unlike {@link #dropSchemaIfExists(String)},
     * this method propagates the SQLException so the caller can abort the
     * deletion transaction and avoid marking the tenant DELETED while data
     * still exists on disk.
     *
     * <p>Same guard regex as {@link #dropSchemaIfExists(String)}.
     *
     * @param schemaName the schema name to drop (e.g., "kv_abc123")
     * @throws RuntimeException wrapping SQLException if the DROP fails
     */
    public void dropSchemaForDeletion(String schemaName) {
        TenantSchema.validate(schemaName); // IllegalArgumentException (length only, no raw value)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("Dropped tenant schema for deletion: {}", schemaName);
        } catch (SQLException e) {
            log.error("Failed to drop schema '{}' for deletion: {}", schemaName, e.getMessage(), e);
            throw new RuntimeException("Failed to drop schema: " + schemaName, e);
        }
    }

    // ── Private steps ─────────────────────────────────────────────────────────

    private void createSchema(Connection conn, String schemaName) throws SQLException {
        TenantSchema.validate(schemaName); // provision() already validated it; re-checked at use
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
        }
    }

    /** The foundation scripts, in module then version order, then the keyset pull indexes. */
    private void createTables(Connection conn, String schemaName) throws SQLException {
        TenantSchema.validate(schemaName); // provision() already validated it; re-checked at use
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schemaName + "\"");
            for (FoundationScripts.Script script : foundation.scripts()) {
                try {
                    stmt.execute(script.sql()); // a whole file: the driver splits its statements
                } catch (SQLException e) {
                    throw new SQLException("Foundation script " + script.moduleId() + "/" + script.name()
                            + " failed: " + e.getMessage(), e.getSQLState(), e);
                }
            }
            // Story 11.5 — keyset pull indexes (every delta table exists at this point, still empty).
            // Best effort: a failed index never fails provisioning; SyncKeysetIndexJob repairs it.
            createSyncKeysetIndexes(conn, stmt, schemaName);
        }
    }

    /** Indexes built at provisioning; package-private so a test can inject a failing one. */
    List<SyncKeysetIndex> syncKeysetIndexes = SyncKeysetIndex.SYNC_KEYSET_INDEXES;

    /**
     * Story 11.5 — creates each keyset pull index on its own, under a savepoint: a failure is logged with its cause
     * and skipped without aborting the rest of the provisioning.
     */
    private void createSyncKeysetIndexes(Connection conn, Statement stmt, String schemaName) throws SQLException {
        for (SyncKeysetIndex index : syncKeysetIndexes) {
            if (index.moduleTable()) continue; // created by the module's migration with its table (15.5b)
            Savepoint savepoint = conn.setSavepoint();
            try {
                stmt.execute(index.createSql());
                conn.releaseSavepoint(savepoint);
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback(savepoint);
                } catch (SQLException | RuntimeException rollbackFailure) {
                    log.warn("Keyset index {}: rollback to savepoint failed on schema {}",
                            index.name(), schemaName, rollbackFailure);
                }
                log.warn("Keyset index {} not created on schema {} — left to SyncKeysetIndexJob",
                        index.name(), schemaName, e);
            }
        }
    }

    private void seedData(Connection conn, String schemaName, PlanType trialPlan, int trialMonths)
            throws SQLException {
        TenantSchema.validate(schemaName); // provision() already validated it; re-checked at use
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO \"" + schemaName + "\"");
            stmt.execute(SEED_ROLES);
            boolean profiles = planProfilesPresent(stmt);
            try (PreparedStatement seed = conn.prepareStatement(profiles ? SEED_SUBSCRIPTION
                    : SEED_SUBSCRIPTION_DEFAULTS)) {
                int i = 1;
                seed.setString(i++, trialPlan.code());
                if (profiles) {
                    for (int k = 0; k < 3; k++) {
                        seed.setString(i++, trialPlan.code());
                    }
                }
                seed.setInt(i, trialMonths);
                seed.execute();
            }
            stmt.execute(SEED_STORE);
        }
    }

    /** Story 16.8 — whether the public plan profiles exist (V33): the subscription seed reads them when they do. */
    private static boolean planProfilesPresent(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(PLAN_PROFILES_PRESENT)) {
            return rs != null && rs.next() && rs.getBoolean(1); // null: statement test doubles
        }
    }
}
