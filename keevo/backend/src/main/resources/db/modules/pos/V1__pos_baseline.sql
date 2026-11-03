-- ─────────────────────────────────────────────────────────────────────────────
-- Module pos — V1 baseline (Story 16.12)
--
-- Sales, sale lines and day closures of a tenant.
-- Tables: sales, sale_items, day_closures.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_pos). It is the single
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
-- Never DROP a table or column, never UPDATE/DELETE (except the received_at backfill of a newly added column, below): no data is changed.
--
-- MIGRATION POLICY: once released this file is applied — never edit it. A change is a new versioned script
-- (V2__…), idempotent as well. validateOnMigrate is on: an edited applied script makes provisioning fail loudly
-- (checksum mismatch) until it is restored.
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start/request)
SET LOCAL lock_timeout = '10s';
-- Runs after crm (sales.client_id → clients) and inventory.

-- ── sales ───────────────────────────────────────────────────────────────────────
-- Created with its client foreign key when the crm tables exist (crm always runs first). An existing sales table
-- never gets the foreign key added (policy above).
DO $$
BEGIN
    IF to_regclass('sales') IS NULL THEN
        CREATE TABLE sales (
            id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
            store_id     UUID        NOT NULL,
            employee_id  UUID        NOT NULL,
            client_id    UUID,
            total_amount INTEGER     NOT NULL DEFAULT 0,
            payment_mode VARCHAR(30) NOT NULL DEFAULT 'CASH',
            created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
        );
        IF to_regclass('clients') IS NOT NULL THEN
            ALTER TABLE sales ADD CONSTRAINT sales_client_id_fkey FOREIGN KEY (client_id) REFERENCES clients(id);
        END IF;
    END IF;
END
$$;
-- Story 2.5 client_id; 4.1 status, occurred_at; 4.2 discount_amount; 5.2 updated_at; v1s-13-10 version;
-- mobile_money_ref (Mobile Money reference typed at checkout)
ALTER TABLE sales ADD COLUMN IF NOT EXISTS client_id        UUID;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS total_amount     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS payment_mode     VARCHAR(30) NOT NULL DEFAULT 'CASH'::character varying;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE sales ADD COLUMN IF NOT EXISTS status           VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','CANCELLED'));
ALTER TABLE sales ADD COLUMN IF NOT EXISTS occurred_at      TIMESTAMPTZ;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS discount_amount  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS updated_at       TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE sales ADD COLUMN IF NOT EXISTS version          BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sales ADD COLUMN IF NOT EXISTS mobile_money_ref VARCHAR(100);
-- Story 4.3: the status CHECK accepts PENDING_VALIDATION
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'sales'::regclass AND conname = 'sales_status_check'
                     AND pg_get_constraintdef(oid) LIKE '%PENDING_VALIDATION%') THEN
        ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_status_check;
        -- NOT VALID + guarded VALIDATE: a legacy row outside the list never aborts the tenant upgrade
        ALTER TABLE sales ADD CONSTRAINT sales_status_check CHECK (status IN ('COMPLETED','CANCELLED','PENDING_VALIDATION')) NOT VALID;
        BEGIN
            ALTER TABLE sales VALIDATE CONSTRAINT sales_status_check;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'sales_status_check left NOT VALID in %: legacy rows outside the list', current_schema();
        END;
    END IF;
END
$$;
CREATE INDEX IF NOT EXISTS idx_sales_client_id ON sales(client_id);
CREATE INDEX IF NOT EXISTS idx_sales_store_occurred_at ON sales(store_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_sales_store_employee_occurred ON sales(store_id, employee_id, occurred_at);
-- idx_sales_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── sale_items ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sale_items (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id            UUID         NOT NULL REFERENCES sales(id),
    product_id         UUID         NOT NULL,
    variant_id         UUID,
    product_name       VARCHAR(255) NOT NULL,
    applied_unit_price INTEGER      NOT NULL,
    quantity           INTEGER      NOT NULL CHECK (quantity > 0),
    subtotal           INTEGER      NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- Story 4.2 catalogue_unit_price, Story 5.2 updated_at
ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS variant_id           UUID;
ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS created_at           TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS catalogue_unit_price INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS updated_at           TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_sale_items_sale_id ON sale_items(sale_id);

-- ── day_closures ────────────────────────────────────────────────────────────────
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
    received_at         TIMESTAMPTZ,
    CONSTRAINT uq_day_closure_store_date UNIQUE (store_id, closure_date)
);
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS actor_id            UUID;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS closed_at           TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS is_automatic        BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS tenant_id           VARCHAR(64);
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS total_sales         INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS total_revenue       INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS top_product_id      VARCHAR(64);
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS top_product_name    VARCHAR(255);
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS top_product_qty     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS cash_amount         INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS momo_amount         INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS pending_sales_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE day_closures ADD COLUMN IF NOT EXISTS pending_sales_total INTEGER NOT NULL DEFAULT 0;
-- UI-3 received_at: server time a closure was recorded (pull cursor; an offline closure keeps its earlier device time
-- in closed_at). No column default: when the column is added here, its existing rows get received_at = closed_at
-- (the only UPDATE of this script, on the newly added column); the application always writes it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'day_closures'
                     AND column_name = 'received_at') THEN
        ALTER TABLE day_closures ADD COLUMN received_at TIMESTAMPTZ;
        UPDATE day_closures SET received_at = closed_at WHERE received_at IS NULL;
    END IF;
END
$$;
CREATE INDEX IF NOT EXISTS idx_day_closures_store_date ON day_closures(store_id, closure_date DESC);
CREATE INDEX IF NOT EXISTS idx_day_closures_received_at ON day_closures(received_at, id);
-- idx_day_closures_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'sales'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE sales ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'sales primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'sale_items'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE sale_items ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'sale_items primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'day_closures'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE day_closures ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'day_closures primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('sales', 'id', $d$gen_random_uuid()$d$),
                     ('sales', 'total_amount', $d$0$d$),
                     ('sales', 'payment_mode', $d$'CASH'::character varying$d$),
                     ('sales', 'created_at', $d$now()$d$),
                     ('sales', 'status', $d$'COMPLETED'::character varying$d$),
                     ('sales', 'discount_amount', $d$0$d$),
                     ('sales', 'updated_at', $d$now()$d$),
                     ('sales', 'version', $d$0$d$),
                     ('sale_items', 'id', $d$gen_random_uuid()$d$),
                     ('sale_items', 'created_at', $d$now()$d$),
                     ('sale_items', 'catalogue_unit_price', $d$0$d$),
                     ('sale_items', 'updated_at', $d$now()$d$),
                     ('day_closures', 'id', $d$gen_random_uuid()$d$),
                     ('day_closures', 'closed_at', $d$now()$d$),
                     ('day_closures', 'is_automatic', $d$false$d$),
                     ('day_closures', 'total_sales', $d$0$d$),
                     ('day_closures', 'total_revenue', $d$0$d$),
                     ('day_closures', 'top_product_qty', $d$0$d$),
                     ('day_closures', 'cash_amount', $d$0$d$),
                     ('day_closures', 'momo_amount', $d$0$d$),
                     ('day_closures', 'pending_sales_count', $d$0$d$),
                     ('day_closures', 'pending_sales_total', $d$0$d$)) AS d(table_name, column_name, expr)
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
