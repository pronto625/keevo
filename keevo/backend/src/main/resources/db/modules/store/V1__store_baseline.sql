-- ─────────────────────────────────────────────────────────────────────────────
-- Module store — V1 baseline (Story 16.12)
--
-- The stores (shops and warehouses) of a tenant.
-- Tables: stores.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_store). It is the single
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

-- ── stores ──────────────────────────────────────────────────────────────────────
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
);
-- Story 3.1: address, phone, type on stores created before them
ALTER TABLE stores ADD COLUMN IF NOT EXISTS address    VARCHAR(255);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS phone      VARCHAR(30);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS type       VARCHAR(10) NOT NULL DEFAULT 'STORE' CHECK (type IN ('STORE', 'WAREHOUSE'));
ALTER TABLE stores ADD COLUMN IF NOT EXISTS is_active  BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE stores ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE stores ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_stores_type ON stores(type);
-- idx_stores_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'stores'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE stores ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'stores primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('stores', 'id', $d$gen_random_uuid()$d$),
                     ('stores', 'type', $d$'STORE'::character varying$d$),
                     ('stores', 'is_active', $d$true$d$),
                     ('stores', 'created_at', $d$now()$d$),
                     ('stores', 'updated_at', $d$now()$d$)) AS d(table_name, column_name, expr)
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
