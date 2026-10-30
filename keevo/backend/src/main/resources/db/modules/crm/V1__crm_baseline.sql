-- ─────────────────────────────────────────────────────────────────────────────
-- Module crm — V1 baseline (Story 13.9)
--
-- Reproduces the tenant DDL of the crm tables as TenantSchemaProvisioner creates them (Story 2.5):
-- clients, suppliers, product_suppliers — columns, defaults, primary keys, indexes, foreign keys.
-- Runs inside the tenant schema (Flyway search_path = the tenant schema; history flyway_history_crm).
--
-- IDEMPOTENT ON PURPOSE: every existing tenant already has these tables (TenantSchemaProvisioner, or the
-- earlier public → tenant copy). On an up-to-date tenant this script changes nothing; on a tenant that
-- drifted it only ADDS what is missing:
--   • a missing table / column / index / primary key is created;
--   • a column without its default gets the default (metadata only, existing rows untouched);
--   • foreign keys are NOT added to an existing table (it could refuse writes the application does
--     today, or fail on old rows) — they come with the table only when it is created here.
-- Never DROP, never UPDATE/DELETE: no data is changed.
--
-- MIGRATION POLICY: this file is applied — never edit it. A change is a new versioned script
-- (V2__…). validateOnMigrate is on: an edited applied script makes provisioning fail loudly
-- (checksum mismatch) until it is restored; see the 13.9 spec for the manual repair procedure.
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start)
SET LOCAL lock_timeout = '10s';

-- ── clients ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS clients (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    phone      VARCHAR(30)  NOT NULL,
    email      VARCHAR(255),
    notes      TEXT,
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS email      VARCHAR(255);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS notes      TEXT;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS archived   BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE clients ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ── suppliers ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS suppliers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL,
    phone      VARCHAR(30)  NOT NULL,
    email      VARCHAR(255),
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS email      VARCHAR(255);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS archived   BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- ── product_suppliers ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_suppliers (
    product_id  UUID NOT NULL REFERENCES products(id),
    supplier_id UUID NOT NULL REFERENCES suppliers(id),
    PRIMARY KEY (product_id, supplier_id)
);

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'clients'::regclass AND contype = 'p') THEN
        ALTER TABLE clients ADD PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'suppliers'::regclass AND contype = 'p') THEN
        ALTER TABLE suppliers ADD PRIMARY KEY (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'product_suppliers'::regclass AND contype = 'p') THEN
        ALTER TABLE product_suppliers ADD PRIMARY KEY (product_id, supplier_id);
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('clients', 'id', 'gen_random_uuid()'),
                     ('clients', 'archived', 'FALSE'),
                     ('clients', 'created_at', 'NOW()'),
                     ('clients', 'updated_at', 'NOW()'),
                     ('suppliers', 'id', 'gen_random_uuid()'),
                     ('suppliers', 'archived', 'FALSE'),
                     ('suppliers', 'created_at', 'NOW()'),
                     ('suppliers', 'updated_at', 'NOW()')) AS d(table_name, column_name, expr)
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

-- ── indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_clients_name ON clients(name);
CREATE INDEX IF NOT EXISTS idx_clients_archived ON clients(archived);
CREATE INDEX IF NOT EXISTS idx_suppliers_name ON suppliers(name);
CREATE INDEX IF NOT EXISTS idx_product_suppliers_supplier ON product_suppliers(supplier_id);
-- idx_clients_sync_keyset / idx_suppliers_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).
