-- ============================================================================
-- V1__create_tenant_schema.sql
-- Tenant-specific schema tables: all business data for one isolated tenant
-- Applied programmatically by FlywayTenantMigration.migrate() per tenant
-- ============================================================================

-- ── USERS (tenant-local registry) ────────────────────────────────────────────
-- Tenant-scoped user roster (mirrors public.users for auth; extended by Story 3.5 employees)
-- public.users is the global auth registry; this table is for tenant business logic
CREATE TABLE IF NOT EXISTS users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number  VARCHAR(20)  UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER', 'EMPLOYEE')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_users_phone ON users(phone_number);

-- ── SUBSCRIPTIONS ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscriptions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_type       VARCHAR(20) NOT NULL DEFAULT 'FREE'
                        CHECK (plan_type IN ('FREE', 'PREMIUM')),
    max_stores      INT         NOT NULL DEFAULT 3,
    max_products    INT         NOT NULL DEFAULT 500,
    max_employees   INT         NOT NULL DEFAULT 5,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXPIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ
);

-- ── ROLES ────────────────────────────────────────────────────────────────────
-- Role definitions for this tenant (AC1: OWNER + EMPLOYEE with permissions)
CREATE TABLE IF NOT EXISTS roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50)  UNIQUE NOT NULL,
    permissions JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── USER ROLES ───────────────────────────────────────────────────────────────
-- Maps users to roles within this tenant
CREATE TABLE IF NOT EXISTS user_roles (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  UUID NOT NULL,
    role_id  UUID NOT NULL REFERENCES roles(id),
    UNIQUE(user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_user_roles_user ON user_roles(user_id);

-- ── STORES ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stores (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── WAREHOUSES ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS warehouses (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    store_id    UUID         REFERENCES stores(id),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── PRODUCTS ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    unit_price      BIGINT       NOT NULL DEFAULT 0,   -- XAF, no decimals
    cost_price      BIGINT       NOT NULL DEFAULT 0,   -- XAF, no decimals
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);

-- ── STOCK LEVELS ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_levels (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID        NOT NULL REFERENCES products(id),
    store_id    UUID        NOT NULL REFERENCES stores(id),
    quantity    INT         NOT NULL DEFAULT 0,
    threshold   INT         NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, store_id)
);

CREATE INDEX IF NOT EXISTS idx_stock_levels_product  ON stock_levels(product_id);
CREATE INDEX IF NOT EXISTS idx_stock_levels_store    ON stock_levels(store_id);

-- ── SALES ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sales (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID        NOT NULL REFERENCES stores(id),
    seller_id       UUID,                               -- User UUID (no FK — flexible)
    total_amount    BIGINT      NOT NULL DEFAULT 0,     -- XAF
    discount_amount BIGINT      NOT NULL DEFAULT 0,     -- XAF
    payment_mode    VARCHAR(20) NOT NULL DEFAULT 'CASH'
                        CHECK (payment_mode IN ('CASH', 'MOBILE_MONEY', 'CARD', 'CREDIT')),
    sale_date       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    synced          BOOLEAN     NOT NULL DEFAULT FALSE,
    synced_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sales_store      ON sales(store_id);
CREATE INDEX IF NOT EXISTS idx_sales_date       ON sales(sale_date);
CREATE INDEX IF NOT EXISTS idx_sales_seller     ON sales(seller_id);

-- ── SALE ITEMS ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sale_items (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id     UUID    NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    product_id  UUID    NOT NULL REFERENCES products(id),
    quantity    INT     NOT NULL DEFAULT 1,
    unit_price  BIGINT  NOT NULL,   -- XAF — price at time of sale (snapshot)
    total_price BIGINT  NOT NULL,   -- XAF
    synced_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sale_items_sale    ON sale_items(sale_id);
CREATE INDEX IF NOT EXISTS idx_sale_items_product ON sale_items(product_id);

-- ── AUDIT LOG ────────────────────────────────────────────────────────────────
-- Append-only audit trail — Story 1.7 adds row-level security (no UPDATE/DELETE)
CREATE TABLE IF NOT EXISTS audit_log (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID,                                  -- Actor (nullable for system events)
    entity_type  VARCHAR(50)  NOT NULL,
    entity_id    UUID,
    action       VARCHAR(50)  NOT NULL,
    value_before JSONB,
    value_after  JSONB,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity     ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_occurred   ON audit_log(occurred_at DESC);

-- ── SYNC QUEUE ───────────────────────────────────────────────────────────────
-- Operations queued for offline sync (Story 5.x)
CREATE TABLE IF NOT EXISTS sync_queue (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    operation   VARCHAR(50) NOT NULL,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    synced      BOOLEAN     NOT NULL DEFAULT FALSE,
    synced_at   TIMESTAMPTZ           -- PURGE RULE: only purge WHERE synced_at IS NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sync_queue_pending ON sync_queue(synced) WHERE synced = FALSE;

-- ── NOTIFICATIONS ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type        VARCHAR(50)  NOT NULL,
    message     TEXT         NOT NULL,
    read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
