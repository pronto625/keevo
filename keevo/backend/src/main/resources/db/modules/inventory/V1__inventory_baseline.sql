-- ─────────────────────────────────────────────────────────────────────────────
-- Module inventory — V1 baseline (Story 16.12)
--
-- Stock levels, movements, transfers and inventory sessions of a tenant (the assisted counting
-- screens are the paid "counting" licence; their tables exist for every tenant: sync reads them).
-- Tables: stock_levels, stock_movements, stock_transfers, inventory_sessions, inventory_counts.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_inventory). It is the single
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
-- Requires the catalog tables (products) and the store tables (stores): both modules run before this one.

-- ── stock_levels ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_levels (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id        UUID        NOT NULL REFERENCES products(id),
    variant_id        UUID,
    store_id          UUID        NOT NULL,
    quantity          INTEGER     NOT NULL DEFAULT 0,
    minimum_threshold INTEGER     NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_level UNIQUE (product_id, store_id)
);
-- minimum_threshold, Story v1s-13-1 version (optimistic lock)
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS variant_id        UUID;
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS quantity          INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS minimum_threshold INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS updated_at        TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS version           BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_stock_levels_product_store ON stock_levels(product_id, store_id);
-- idx_stock_levels_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── stock_movements ─────────────────────────────────────────────────────────────
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
);
ALTER TABLE stock_movements ADD COLUMN IF NOT EXISTS variant_id      UUID;
ALTER TABLE stock_movements ADD COLUMN IF NOT EXISTS notes           TEXT;
ALTER TABLE stock_movements ADD COLUMN IF NOT EXISTS occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_stock_movements_product ON stock_movements(product_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_movements_store ON stock_movements(store_id, occurred_at DESC);
-- Story v1s-13-5: movement_type accepts SALE_CANCELLED. An inherited stock_movements_movement_type_check (old
-- LIKE copy) lacking it is replaced; when no ck_movement_type accepts it, ck_movement_type is re-added with the full
-- list (NOT VALID + guarded VALIDATE: a legacy row never aborts the upgrade).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'stock_movements'::regclass
                 AND conname = 'stock_movements_movement_type_check'
                 AND pg_get_constraintdef(oid) NOT LIKE '%SALE_CANCELLED%') THEN
        ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_movement_type_check;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stock_movements'::regclass
                     AND conname = 'ck_movement_type'
                     AND pg_get_constraintdef(oid) LIKE '%SALE_CANCELLED%') THEN
        ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS ck_movement_type;
        ALTER TABLE stock_movements DROP CONSTRAINT IF EXISTS stock_movements_movement_type_check;
        ALTER TABLE stock_movements ADD CONSTRAINT ck_movement_type
            CHECK (movement_type IN
                ('SALE','STOCK_ENTRY','TRANSFER_IN','TRANSFER_OUT','ADJUSTMENT','SALE_CANCELLED'))
            NOT VALID;
        BEGIN
            ALTER TABLE stock_movements VALIDATE CONSTRAINT ck_movement_type;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'ck_movement_type left NOT VALID in %: legacy rows with another movement_type',
                current_schema();
        END;
    END IF;
EXCEPTION WHEN duplicate_object THEN NULL;
END
$$;
-- idx_stock_movements_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── stock_transfers ─────────────────────────────────────────────────────────────
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
    notes                TEXT,
    version              BIGINT      NOT NULL DEFAULT 0
);
-- Story v1s-13-1 version (optimistic lock), Story 5.2 updated_at (delta pull on status changes)
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS variant_id           UUID;
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS status               VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'::character varying;
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS notes                TEXT;
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS version              BIGINT NOT NULL DEFAULT 0;
ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS updated_at           TIMESTAMPTZ;
-- Story 3.3 hotfix (runner M1): the status CHECK accepts IN_TRANSIT
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stock_transfers'::regclass AND conname = 'stock_transfers_status_check'
                     AND pg_get_constraintdef(oid) LIKE '%IN_TRANSIT%') THEN
        ALTER TABLE stock_transfers DROP CONSTRAINT IF EXISTS stock_transfers_status_check;
        -- NOT VALID + guarded VALIDATE: a legacy row outside the list never aborts the tenant upgrade
        ALTER TABLE stock_transfers ADD CONSTRAINT stock_transfers_status_check CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT','IN_TRANSIT')) NOT VALID;
        BEGIN
            ALTER TABLE stock_transfers VALIDATE CONSTRAINT stock_transfers_status_check;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'stock_transfers_status_check left NOT VALID in %: legacy rows outside the list', current_schema();
        END;
    END IF;
END
$$;
CREATE INDEX IF NOT EXISTS idx_stock_transfers_source ON stock_transfers(source_store_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_transfers_dest ON stock_transfers(destination_store_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_transfers_product ON stock_transfers(product_id, occurred_at DESC);
-- idx_stock_transfers_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── inventory_sessions ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inventory_sessions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID        NOT NULL REFERENCES stores(id),
    scope        VARCHAR(10) NOT NULL CHECK (scope IN ('FULL', 'PARTIAL')),
    category_ids JSONB,
    status       VARCHAR(15) NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS', 'VALIDATED', 'CANCELLED')),
    started_by   UUID        NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cancelled_by UUID,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS category_ids JSONB;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS status       VARCHAR(15) NOT NULL DEFAULT 'IN_PROGRESS'::character varying;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS started_at   TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS cancelled_by UUID;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE inventory_sessions ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_inventory_sessions_store_status ON inventory_sessions(store_id, status);
CREATE INDEX IF NOT EXISTS idx_inventory_sessions_status ON inventory_sessions(status);
-- idx_inventory_sessions_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── inventory_counts ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inventory_counts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID         NOT NULL REFERENCES inventory_sessions(id),
    product_id    UUID         NOT NULL,
    variant_id    UUID,
    product_name  VARCHAR(200) NOT NULL,
    variant_label VARCHAR(100),
    theoretical   INT          NOT NULL DEFAULT 0,
    physical      INT,
    counted_at    TIMESTAMPTZ,
    counted_by    UUID,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS variant_id    UUID;
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS variant_label VARCHAR(100);
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS theoretical   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS physical      INTEGER;
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS counted_at    TIMESTAMPTZ;
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS counted_by    UUID;
ALTER TABLE inventory_counts ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_inventory_counts_session ON inventory_counts(session_id);
-- one count per (session, product[, variant]); existing duplicates leave the index out with a WARNING
DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_no_variant
        ON inventory_counts(session_id, product_id) WHERE variant_id IS NULL;
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'idx_inventory_counts_session_product_no_variant skipped in %: duplicate rows already present', current_schema();
END
$$;
DO $$
BEGIN
    CREATE UNIQUE INDEX IF NOT EXISTS idx_inventory_counts_session_product_variant
        ON inventory_counts(session_id, product_id, variant_id) WHERE variant_id IS NOT NULL;
EXCEPTION WHEN unique_violation THEN
    RAISE WARNING 'idx_inventory_counts_session_product_variant skipped in %: duplicate rows already present', current_schema();
END
$$;
-- idx_inventory_counts_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stock_levels'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE stock_levels ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'stock_levels primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stock_movements'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE stock_movements ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'stock_movements primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stock_transfers'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE stock_transfers ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'stock_transfers primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'inventory_sessions'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE inventory_sessions ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'inventory_sessions primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'inventory_counts'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE inventory_counts ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'inventory_counts primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('stock_levels', 'id', $d$gen_random_uuid()$d$),
                     ('stock_levels', 'quantity', $d$0$d$),
                     ('stock_levels', 'minimum_threshold', $d$0$d$),
                     ('stock_levels', 'updated_at', $d$now()$d$),
                     ('stock_levels', 'version', $d$0$d$),
                     ('stock_movements', 'id', $d$gen_random_uuid()$d$),
                     ('stock_movements', 'occurred_at', $d$now()$d$),
                     ('stock_transfers', 'id', $d$gen_random_uuid()$d$),
                     ('stock_transfers', 'occurred_at', $d$now()$d$),
                     ('stock_transfers', 'status', $d$'COMPLETED'::character varying$d$),
                     ('stock_transfers', 'version', $d$0$d$),
                     ('inventory_sessions', 'id', $d$gen_random_uuid()$d$),
                     ('inventory_sessions', 'status', $d$'IN_PROGRESS'::character varying$d$),
                     ('inventory_sessions', 'started_at', $d$now()$d$),
                     ('inventory_sessions', 'updated_at', $d$now()$d$),
                     ('inventory_counts', 'id', $d$gen_random_uuid()$d$),
                     ('inventory_counts', 'theoretical', $d$0$d$),
                     ('inventory_counts', 'updated_at', $d$now()$d$)) AS d(table_name, column_name, expr)
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
