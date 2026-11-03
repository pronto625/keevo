-- ─────────────────────────────────────────────────────────────────────────────
-- Module catalog — V1 baseline (Story 16.12)
--
-- The product catalogue of a tenant.
-- Tables: categories, products.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_catalog). It is the single
-- author of these tables: at registration TenantSchemaProvisioner executes it inside the registration transaction
-- (foundation modules in module order), then Flyway re-applies it after commit and records the history; on an
-- existing tenant Flyway runs it once.
--
-- IDEMPOTENT FOREVER (it runs twice on every new tenant, and on tenants built by any earlier code — the programmatic
-- provisioner, or the old public → tenant LIKE copy): on an up-to-date tenant it changes nothing; on a tenant that
-- drifted it only ADDS what is missing:
--   • a missing table / column / index / primary key is created; a missing column gets today's default;
--   • a column without its default gets the default (metadata only, existing rows untouched);
--   • foreign keys are NOT added to an existing table (it could refuse writes the application does today, or fail
--     on old rows) — they come with the table only when it is created here.
-- Never DROP a table or column, never UPDATE/DELETE: no data is changed.
--
-- MIGRATION POLICY: once released this file is applied — never edit it. A change is a new versioned script
-- (V2__…), idempotent as well. validateOnMigrate is on: an edited applied script makes provisioning fail loudly
-- (checksum mismatch) until it is restored.
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start/request)
SET LOCAL lock_timeout = '10s';

-- ── categories ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS categories (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL,
    parent_id  UUID         REFERENCES categories(id) ON DELETE SET NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    is_custom  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
ALTER TABLE categories ADD COLUMN IF NOT EXISTS parent_id  UUID;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS is_active  BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS is_custom  BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE categories ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories(parent_id);
CREATE INDEX IF NOT EXISTS idx_categories_is_active ON categories(is_active);
-- idx_categories_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── products ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    sku            VARCHAR(20)  NOT NULL,
    category_id    UUID         REFERENCES categories(id),
    price          INTEGER      NOT NULL DEFAULT 0,
    buy_price      INTEGER      NOT NULL DEFAULT 0,
    transport_cost INTEGER      NOT NULL DEFAULT 0,
    stock_quantity INTEGER      NOT NULL DEFAULT 0,
    photo_url      VARCHAR(500),
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'DRAFT'))
);
-- transport_cost, Story 2.3 minimum_threshold, Story 17.12 track_stock (TRUE: existing products stay tracked)
ALTER TABLE products ADD COLUMN IF NOT EXISTS description       TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS category_id       UUID;
ALTER TABLE products ADD COLUMN IF NOT EXISTS price             INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS buy_price         INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS transport_cost    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS stock_quantity    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS photo_url         VARCHAR(500);
ALTER TABLE products ADD COLUMN IF NOT EXISTS archived          BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE products ADD COLUMN IF NOT EXISTS status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'::character varying;
ALTER TABLE products ADD COLUMN IF NOT EXISTS created_at        TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE products ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE products ADD COLUMN IF NOT EXISTS minimum_threshold INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS track_stock       BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku);
CREATE INDEX IF NOT EXISTS idx_products_archived ON products(archived);
-- Story 2.4: case-insensitive unique name (safety net); existing duplicates leave it out with a WARNING
DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS uq_products_name ON products(lower(name));
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'uq_products_name skipped in %: duplicate rows already present', current_schema();
END
$$;
-- idx_products_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'categories'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE categories ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'categories primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'products'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE products ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'products primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('categories', 'id', $d$gen_random_uuid()$d$),
                     ('categories', 'is_active', $d$true$d$),
                     ('categories', 'is_custom', $d$false$d$),
                     ('categories', 'created_at', $d$now()$d$),
                     ('categories', 'updated_at', $d$now()$d$),
                     ('products', 'id', $d$gen_random_uuid()$d$),
                     ('products', 'price', $d$0$d$),
                     ('products', 'buy_price', $d$0$d$),
                     ('products', 'transport_cost', $d$0$d$),
                     ('products', 'stock_quantity', $d$0$d$),
                     ('products', 'archived', $d$false$d$),
                     ('products', 'status', $d$'ACTIVE'::character varying$d$),
                     ('products', 'created_at', $d$now()$d$),
                     ('products', 'updated_at', $d$now()$d$),
                     ('products', 'minimum_threshold', $d$0$d$),
                     ('products', 'track_stock', $d$true$d$)) AS d(table_name, column_name, expr)
        JOIN information_schema.columns c
          ON c.table_schema = current_schema()
         AND c.table_name = d.table_name
         AND c.column_name = d.column_name
        WHERE c.column_default IS NULL
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I SET DEFAULT ' || col.expr, col.table_name, col.column_name);
    END LOOP;
END
$$;
