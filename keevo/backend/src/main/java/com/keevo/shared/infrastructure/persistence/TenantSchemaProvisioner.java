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
                plan_type     VARCHAR(20) NOT NULL DEFAULT 'PREMIUM_TRIAL'
                                  CHECK (plan_type IN ('FREE','PREMIUM_TRIAL','PREMIUM')),
                max_stores    INT         NOT NULL DEFAULT 2147483647,
                max_products  INT         NOT NULL DEFAULT 2147483647,
                max_employees INT         NOT NULL DEFAULT 2147483647,
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
                address    VARCHAR(255),
                phone      VARCHAR(30),
                type       VARCHAR(10)  NOT NULL DEFAULT 'STORE'
                             CHECK (type IN ('STORE', 'WAREHOUSE')),
                is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    /** Migration DDL: adds address to existing stores tables (idempotent — Story 3.1) */
    static final String DDL_STORES_MIGRATE_ADDRESS =
            "ALTER TABLE stores ADD COLUMN IF NOT EXISTS address VARCHAR(255)";

    /** Migration DDL: adds phone to existing stores tables (idempotent — Story 3.1) */
    static final String DDL_STORES_MIGRATE_PHONE =
            "ALTER TABLE stores ADD COLUMN IF NOT EXISTS phone VARCHAR(30)";

    /** Migration DDL: adds type to existing stores tables (idempotent — Story 3.1) */
    static final String DDL_STORES_MIGRATE_TYPE =
            "ALTER TABLE stores ADD COLUMN IF NOT EXISTS type VARCHAR(10) NOT NULL DEFAULT 'STORE' " +
            "CHECK (type IN ('STORE', 'WAREHOUSE'))";

    static final String DDL_STORES_IDX_TYPE =
            "CREATE INDEX IF NOT EXISTS idx_stores_type ON stores(type)";

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

    static final String DDL_PRODUCTS = """
            CREATE TABLE IF NOT EXISTS products (
                id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                name         VARCHAR(200) NOT NULL,
                description  TEXT,
                sku          VARCHAR(20) NOT NULL,
                category_id  UUID        REFERENCES categories(id),
                price        INTEGER     NOT NULL DEFAULT 0,
                buy_price    INTEGER     NOT NULL DEFAULT 0,
                transport_cost INTEGER   NOT NULL DEFAULT 0,
                stock_quantity INTEGER   NOT NULL DEFAULT 0,
                photo_url    VARCHAR(500),
                archived     BOOLEAN     NOT NULL DEFAULT FALSE,
                status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                
                CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'DRAFT'))
            )""";

    /** Migration DDL: adds transport_cost to existing products tables (idempotent) */
    static final String DDL_PRODUCTS_MIGRATE_TRANSPORT_COST =
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS transport_cost INTEGER NOT NULL DEFAULT 0";

    /** Migration DDL: adds minimum_threshold to existing products tables (idempotent — Story 2.3) */
    static final String DDL_PRODUCTS_MIGRATE_MINIMUM_THRESHOLD =
            "ALTER TABLE products ADD COLUMN IF NOT EXISTS minimum_threshold INTEGER NOT NULL DEFAULT 0";

    private static final String DDL_PRODUCTS_IDX_SKU =
            "CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku)";

    private static final String DDL_PRODUCTS_IDX_ARCHIVED =
            "CREATE INDEX IF NOT EXISTS idx_products_archived ON products(archived)";

    /** Story 2.4 — case-insensitive unique constraint on product name (safety net at DB level) */
    static final String DDL_PRODUCTS_UQ_NAME =
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_products_name ON products(lower(name))";

    private static final String DDL_TENANT_PREFERENCES = """
            CREATE TABLE IF NOT EXISTS tenant_preferences (
                id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                sector_type         VARCHAR(30),
                eod_report_time     TIME        NOT NULL DEFAULT '20:00:00',
                stock_alert_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
                created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    // ── Audit log (Story 1.8) ──────────────────────────────────────────────────

    static final String DDL_AUDIT_LOG = """
            CREATE TABLE IF NOT EXISTS audit_log (
                id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id      UUID        NOT NULL,
                entity_type  VARCHAR(50) NOT NULL,
                entity_id    UUID        NOT NULL,
                action       VARCHAR(80) NOT NULL,
                value_before TEXT,
                value_after  TEXT,
                occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_AUDIT_LOG_IDX_ENTITY =
            "CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log(entity_type, entity_id)";

    static final String DDL_AUDIT_LOG_IDX_OCCURRED =
            "CREATE INDEX IF NOT EXISTS idx_audit_log_occurred ON audit_log(occurred_at DESC)";

    // ── Stock levels (Story 2.3) ───────────────────────────────────────────────

    static final String DDL_STOCK_LEVELS = """
            CREATE TABLE IF NOT EXISTS stock_levels (
                id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                product_id        UUID        NOT NULL REFERENCES products(id),
                variant_id        UUID,
                store_id          UUID        NOT NULL,
                quantity          INTEGER     NOT NULL DEFAULT 0,
                updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                CONSTRAINT uq_stock_level UNIQUE (product_id, store_id)
            )""";

    static final String DDL_STOCK_LEVELS_IDX_PRODUCT =
            "CREATE INDEX IF NOT EXISTS idx_stock_levels_product_store ON stock_levels(product_id, store_id)";

    // ── Stock movements (Story 2.3) ────────────────────────────────────────────

    static final String DDL_STOCK_MOVEMENTS = """
            CREATE TABLE IF NOT EXISTS stock_movements (
                id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                product_id      UUID        NOT NULL REFERENCES products(id),
                variant_id      UUID,
                store_id        UUID        NOT NULL,
                movement_type   VARCHAR(30) NOT NULL,
                quantity_before INTEGER     NOT NULL,
                quantity_change INTEGER     NOT NULL,
                quantity_after  INTEGER     NOT NULL,
                actor_id        UUID        NOT NULL,
                notes           TEXT,
                occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                CONSTRAINT ck_movement_type CHECK (movement_type IN
                    ('SALE','STOCK_ENTRY','TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT'))
            )""";

    static final String DDL_STOCK_MOVEMENTS_IDX_PRODUCT =
            "CREATE INDEX IF NOT EXISTS idx_stock_movements_product ON stock_movements(product_id, occurred_at DESC)";

    static final String DDL_STOCK_MOVEMENTS_IDX_STORE =
            "CREATE INDEX IF NOT EXISTS idx_stock_movements_store ON stock_movements(store_id, occurred_at DESC)";

    // ── Stock transfers (Story 3.3) ────────────────────────────────────────────

    static final String DDL_STOCK_TRANSFERS = """
            CREATE TABLE IF NOT EXISTS stock_transfers (
                id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                source_store_id      UUID        NOT NULL,
                destination_store_id UUID        NOT NULL,
                product_id           UUID        NOT NULL REFERENCES products(id),
                variant_id           UUID,
                quantity             INTEGER     NOT NULL,
                actor_id             UUID        NOT NULL,
                occurred_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                status               VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
                                         CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT','IN_TRANSIT')),
                notes                TEXT
            )""";

    /** Migration DDL: adds IN_TRANSIT to status CHECK constraint (idempotent — Story 3.3 hotfix) */
    static final String DDL_STOCK_TRANSFERS_MIGRATE_IN_TRANSIT = """
            DO $$ BEGIN
                ALTER TABLE stock_transfers DROP CONSTRAINT IF EXISTS stock_transfers_status_check;
                ALTER TABLE stock_transfers ADD CONSTRAINT stock_transfers_status_check
                    CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT','IN_TRANSIT'));
            EXCEPTION WHEN duplicate_object THEN NULL;
            END $$""";

    static final String DDL_STOCK_TRANSFERS_IDX_SOURCE =
            "CREATE INDEX IF NOT EXISTS idx_stock_transfers_source ON stock_transfers(source_store_id, occurred_at DESC)";

    static final String DDL_STOCK_TRANSFERS_IDX_DEST =
            "CREATE INDEX IF NOT EXISTS idx_stock_transfers_dest ON stock_transfers(destination_store_id, occurred_at DESC)";

    static final String DDL_STOCK_TRANSFERS_IDX_PRODUCT =
            "CREATE INDEX IF NOT EXISTS idx_stock_transfers_product ON stock_transfers(product_id, occurred_at DESC)";

    // ── Clients (Story 2.5) ────────────────────────────────────────────────────

    static final String DDL_CLIENTS = """
            CREATE TABLE IF NOT EXISTS clients (
                id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                name       VARCHAR(200) NOT NULL,
                phone      VARCHAR(30)  NOT NULL,
                email      VARCHAR(255),
                notes      TEXT,
                archived   BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_CLIENTS_IDX_NAME =
            "CREATE INDEX IF NOT EXISTS idx_clients_name ON clients(name)";

    static final String DDL_CLIENTS_IDX_ARCHIVED =
            "CREATE INDEX IF NOT EXISTS idx_clients_archived ON clients(archived)";

    // ── Suppliers (Story 2.5) ──────────────────────────────────────────────────

    static final String DDL_SUPPLIERS = """
            CREATE TABLE IF NOT EXISTS suppliers (
                id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                name       VARCHAR(200) NOT NULL,
                phone      VARCHAR(30)  NOT NULL,
                email      VARCHAR(255),
                archived   BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_SUPPLIERS_IDX_NAME =
            "CREATE INDEX IF NOT EXISTS idx_suppliers_name ON suppliers(name)";

    // ── Product-Suppliers join (Story 2.5) ─────────────────────────────────────

    static final String DDL_PRODUCT_SUPPLIERS = """
            CREATE TABLE IF NOT EXISTS product_suppliers (
                product_id  UUID NOT NULL REFERENCES products(id),
                supplier_id UUID NOT NULL REFERENCES suppliers(id),
                PRIMARY KEY (product_id, supplier_id)
            )""";

    static final String DDL_PRODUCT_SUPPLIERS_IDX_SUPPLIER =
            "CREATE INDEX IF NOT EXISTS idx_product_suppliers_supplier ON product_suppliers(supplier_id)";

    // ── Sales (Story 2.5) — created here so client_id FK can reference clients ─

    static final String DDL_SALES = """
            CREATE TABLE IF NOT EXISTS sales (
                id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                store_id     UUID        NOT NULL,
                employee_id  UUID        NOT NULL,
                client_id    UUID        REFERENCES clients(id),
                total_amount INTEGER     NOT NULL DEFAULT 0,
                payment_mode VARCHAR(30) NOT NULL DEFAULT 'CASH',
                created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    /** Migration DDL: adds client_id to existing sales tables (idempotent — Story 2.5) */
    static final String DDL_SALES_MIGRATE_CLIENT_ID =
            "ALTER TABLE sales ADD COLUMN IF NOT EXISTS client_id UUID REFERENCES clients(id)";

    static final String DDL_SALES_IDX_CLIENT =
            "CREATE INDEX IF NOT EXISTS idx_sales_client_id ON sales(client_id)";

    // ── Sale migrations (Story 4.1) ──────────────────────────────────────────

    static final String DDL_SALES_MIGRATE_STATUS =
            "ALTER TABLE sales ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','CANCELLED'))";

    static final String DDL_SALES_MIGRATE_OCCURRED_AT =
            "ALTER TABLE sales ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMPTZ";

    static final String DDL_SALE_ITEMS = """
            CREATE TABLE IF NOT EXISTS sale_items (
                id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                sale_id           UUID        NOT NULL REFERENCES sales(id),
                product_id        UUID        NOT NULL,
                variant_id        UUID,
                product_name      VARCHAR(255) NOT NULL,
                applied_unit_price INTEGER    NOT NULL,
                quantity          INTEGER     NOT NULL CHECK (quantity > 0),
                subtotal          INTEGER     NOT NULL,
                created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_SALE_ITEMS_IDX_SALE =
            "CREATE INDEX IF NOT EXISTS idx_sale_items_sale_id ON sale_items(sale_id)";

    // ── Sale status V2 (Story 4.3) — add PENDING_VALIDATION to CHECK constraint
    static final String DDL_SALES_DROP_STATUS_CHECK =
            "ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_status_check";
    static final String DDL_SALES_ADD_STATUS_CHECK_V2 =
            "ALTER TABLE sales ADD CONSTRAINT sales_status_check CHECK (status IN ('COMPLETED','CANCELLED','PENDING_VALIDATION'))";

    // ── Sale discount (Story 4.2) ──────────────────────────────────────────
    static final String DDL_SALES_MIGRATE_DISCOUNT_AMOUNT =
            "ALTER TABLE sales ADD COLUMN IF NOT EXISTS discount_amount INTEGER NOT NULL DEFAULT 0";

    static final String DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE =
            "ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS catalogue_unit_price INTEGER NOT NULL DEFAULT 0";

    // ── Employees (Story 3.5) ─────────────────────────────────────────────────

    static final String DDL_EMPLOYEES = """
            CREATE TABLE IF NOT EXISTS employees (
                id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id                  UUID         NOT NULL,
                store_id                 UUID         NOT NULL,
                first_name               VARCHAR(100) NOT NULL,
                last_name                VARCHAR(100) NOT NULL,
                status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                                             CHECK (status IN ('ACTIVE','INACTIVE')),
                password_change_required BOOLEAN      NOT NULL DEFAULT TRUE,
                created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_EMPLOYEES_IDX_USER =
            "CREATE INDEX IF NOT EXISTS idx_employees_user_id ON employees(user_id)";

    static final String DDL_EMPLOYEES_IDX_STORE =
            "CREATE INDEX IF NOT EXISTS idx_employees_store_id ON employees(store_id)";

    // ── Draft Notifications (Story 2.4) ──────────────────────────────────────

    static final String DDL_DRAFT_NOTIFICATIONS = """
            CREATE TABLE IF NOT EXISTS draft_notifications (
                id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                product_id   UUID        NOT NULL,
                product_name VARCHAR(255) NOT NULL,
                actor_id     UUID        NOT NULL,
                tenant_id    UUID        NOT NULL,
                acknowledged BOOLEAN     NOT NULL DEFAULT FALSE,
                created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""";

    static final String DDL_DRAFT_NOTIFICATIONS_IDX_PRODUCT =
            "CREATE INDEX IF NOT EXISTS idx_draft_notif_product ON draft_notifications(product_id)";

    static final String DDL_DRAFT_NOTIFICATIONS_IDX_PENDING =
            "CREATE INDEX IF NOT EXISTS idx_draft_notif_pending ON draft_notifications(acknowledged) WHERE acknowledged = FALSE";

    // ── Seed data ─────────────────────────────────────────────────────────────

    private static final String SEED_ROLES = """
            INSERT INTO roles (name, permissions) VALUES
                ('OWNER',    '{"all": true}'::jsonb),
                ('EMPLOYEE', '{"pos": true, "inventory_view": true, "stock_view": true}'::jsonb)
            ON CONFLICT (name) DO NOTHING""";

    // ── Day Closures (Story 4.4) ───────────────────────────────────────────────

    static final String DDL_DAY_CLOSURES = """
            CREATE TABLE IF NOT EXISTS day_closures (
                id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                store_id            UUID         NOT NULL,
                actor_id            UUID,
                closed_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                closure_date        DATE         NOT NULL,
                is_automatic        BOOLEAN      NOT NULL DEFAULT FALSE,
                tenant_id           VARCHAR(64),
                total_sales         INTEGER      NOT NULL DEFAULT 0,
                total_revenue       INTEGER      NOT NULL DEFAULT 0,
                top_product_id      VARCHAR(64),
                top_product_name    VARCHAR(255),
                top_product_qty     INTEGER      NOT NULL DEFAULT 0,
                cash_amount         INTEGER      NOT NULL DEFAULT 0,
                momo_amount         INTEGER      NOT NULL DEFAULT 0,
                pending_sales_count INTEGER      NOT NULL DEFAULT 0,
                pending_sales_total INTEGER      NOT NULL DEFAULT 0,
                CONSTRAINT uq_day_closure_store_date UNIQUE (store_id, closure_date)
            )""";

    static final String DDL_DAY_CLOSURES_IDX_STORE_DATE =
            "CREATE INDEX IF NOT EXISTS idx_day_closures_store_date ON day_closures(store_id, closure_date DESC)";

    static final String DDL_SALES_IDX_STORE_OCCURRED_AT =
            "CREATE INDEX IF NOT EXISTS idx_sales_store_occurred_at ON sales(store_id, occurred_at)";

    static final String DDL_SALES_IDX_STORE_EMPLOYEE_OCCURRED_AT =
            "CREATE INDEX IF NOT EXISTS idx_sales_store_employee_occurred ON sales(store_id, employee_id, occurred_at)";

    // Story 5.1 — sync operations log (idempotency guard)
    static final String DDL_SYNC_OPERATIONS_LOG = """
            CREATE TABLE IF NOT EXISTS sync_operations_log (
                id VARCHAR(36) PRIMARY KEY,
                operation_type VARCHAR(50) NOT NULL,
                entity_id VARCHAR(36),
                status VARCHAR(20) NOT NULL,
                error_reason TEXT,
                processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                client_timestamp TIMESTAMPTZ
            )""";
    static final String DDL_SYNC_OPERATIONS_LOG_IDX_ENTITY =
            "CREATE INDEX IF NOT EXISTS idx_sync_ops_log_entity ON sync_operations_log(entity_id)";
    static final String DDL_SYNC_OPERATIONS_LOG_IDX_PROCESSED =
            "CREATE INDEX IF NOT EXISTS idx_sync_ops_log_processed ON sync_operations_log(processed_at)";

    // SPEC CHANGE 2026-03-06: new tenants start on 6-month PREMIUM_TRIAL (unlimited limits)
    // WHERE NOT EXISTS ensures idempotency — provision() can be called multiple times safely.
    private static final String SEED_SUBSCRIPTION = """
            INSERT INTO subscriptions (plan_type, max_stores, max_products, max_employees, status, expires_at)
            SELECT 'PREMIUM_TRIAL', 2147483647, 2147483647, 2147483647, 'ACTIVE',
                   NOW() + INTERVAL '6 months'
            WHERE NOT EXISTS (SELECT 1 FROM subscriptions)""";

    private static final String SEED_STORE = """
            INSERT INTO stores (name)
            SELECT 'Ma Boutique' WHERE NOT EXISTS (SELECT 1 FROM stores)""";

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
            stmt.execute(DDL_STORES_MIGRATE_ADDRESS);  // idempotent: adds address if missing
            stmt.execute(DDL_STORES_MIGRATE_PHONE);    // idempotent: adds phone if missing
            stmt.execute(DDL_STORES_MIGRATE_TYPE);     // idempotent: adds type if missing
            stmt.execute(DDL_STORES_IDX_TYPE);
            stmt.execute(DDL_CATEGORIES);
            stmt.execute(DDL_CATEGORIES_IDX_PARENT);
            stmt.execute(DDL_CATEGORIES_IDX_ACTIVE);
            stmt.execute(DDL_PRODUCTS);
            stmt.execute(DDL_PRODUCTS_IDX_SKU);
            stmt.execute(DDL_PRODUCTS_IDX_ARCHIVED);
            stmt.execute(DDL_PRODUCTS_MIGRATE_TRANSPORT_COST); // idempotent: adds transport_cost if missing
            stmt.execute(DDL_PRODUCTS_MIGRATE_MINIMUM_THRESHOLD); // idempotent: adds minimum_threshold if missing
            stmt.execute(DDL_PRODUCTS_UQ_NAME); // Story 2.4 — case-insensitive unique name index
            stmt.execute(DDL_TENANT_PREFERENCES);
            stmt.execute(DDL_AUDIT_LOG);
            stmt.execute(DDL_AUDIT_LOG_IDX_ENTITY);
            stmt.execute(DDL_AUDIT_LOG_IDX_OCCURRED);
            // Story 2.3 — stock tables
            stmt.execute(DDL_STOCK_LEVELS);
            stmt.execute(DDL_STOCK_LEVELS_IDX_PRODUCT);
            stmt.execute(DDL_STOCK_MOVEMENTS);
            stmt.execute(DDL_STOCK_MOVEMENTS_IDX_PRODUCT);
            stmt.execute(DDL_STOCK_MOVEMENTS_IDX_STORE);
            // Story 3.3 — stock transfers
            stmt.execute(DDL_STOCK_TRANSFERS);
            stmt.execute(DDL_STOCK_TRANSFERS_MIGRATE_IN_TRANSIT); // idempotent: adds IN_TRANSIT to status constraint
            stmt.execute(DDL_STOCK_TRANSFERS_IDX_SOURCE);
            stmt.execute(DDL_STOCK_TRANSFERS_IDX_DEST);
            stmt.execute(DDL_STOCK_TRANSFERS_IDX_PRODUCT);
            // Story 2.5 — contact tables (clients before sales for FK constraint)
            stmt.execute(DDL_CLIENTS);
            stmt.execute(DDL_CLIENTS_IDX_NAME);
            stmt.execute(DDL_CLIENTS_IDX_ARCHIVED);
            stmt.execute(DDL_SUPPLIERS);
            stmt.execute(DDL_SUPPLIERS_IDX_NAME);
            stmt.execute(DDL_PRODUCT_SUPPLIERS);
            stmt.execute(DDL_PRODUCT_SUPPLIERS_IDX_SUPPLIER);
            stmt.execute(DDL_SALES);
            stmt.execute(DDL_SALES_MIGRATE_CLIENT_ID); // idempotent: adds client_id if missing
            stmt.execute(DDL_SALES_IDX_CLIENT);
            // Story 4.1 — sale_items + sale migrations
            stmt.execute(DDL_SALES_MIGRATE_STATUS);
            stmt.execute(DDL_SALES_MIGRATE_OCCURRED_AT);
            stmt.execute(DDL_SALE_ITEMS);
            stmt.execute(DDL_SALE_ITEMS_IDX_SALE);
            // Story 4.3 — expand CHECK constraint to include PENDING_VALIDATION
            stmt.execute(DDL_SALES_DROP_STATUS_CHECK);
            stmt.execute(DDL_SALES_ADD_STATUS_CHECK_V2);
            // Story 4.2 — sale discount + catalogue price migrations
            stmt.execute(DDL_SALES_MIGRATE_DISCOUNT_AMOUNT);
            stmt.execute(DDL_SALE_ITEMS_MIGRATE_CATALOGUE_PRICE);
            // Story 3.5 — employees
            stmt.execute(DDL_EMPLOYEES);
            stmt.execute(DDL_EMPLOYEES_IDX_USER);
            stmt.execute(DDL_EMPLOYEES_IDX_STORE);
            // Story 2.4 — draft notifications
            stmt.execute(DDL_DRAFT_NOTIFICATIONS);
            stmt.execute(DDL_DRAFT_NOTIFICATIONS_IDX_PRODUCT);
            stmt.execute(DDL_DRAFT_NOTIFICATIONS_IDX_PENDING);
            // Story 4.4 — day closures + sales history indexes
            stmt.execute(DDL_DAY_CLOSURES);
            stmt.execute(DDL_DAY_CLOSURES_IDX_STORE_DATE);
            stmt.execute(DDL_SALES_IDX_STORE_OCCURRED_AT);
            stmt.execute(DDL_SALES_IDX_STORE_EMPLOYEE_OCCURRED_AT);
            // Story 5.1 — sync operations log
            stmt.execute(DDL_SYNC_OPERATIONS_LOG);
            stmt.execute(DDL_SYNC_OPERATIONS_LOG_IDX_ENTITY);
            stmt.execute(DDL_SYNC_OPERATIONS_LOG_IDX_PROCESSED);
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
