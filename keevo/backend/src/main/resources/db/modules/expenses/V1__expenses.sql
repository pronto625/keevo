-- ─────────────────────────────────────────────────────────────────────────────
-- Module expenses — V1 (Story 20.10, fusion Keevo × Bref, FUS-FR25)
--
-- The « Dépenses » module's table, created inside the tenant schema (Flyway search_path = the tenant schema; history
-- flyway_history_expenses) only for tenants that use the module — never in public, no JPA entity. Applied lazily on
-- the first gated call, sync operation or pull (ModuleProvisioner.ensureProvisioned).
--
-- Bref rules (api-bref src/expense): 10 category keys, 5 payment method keys, amount 0.01 … 999 999 999 999 with at
-- most 2 decimals (NUMERIC(14,2)), a calendar date. A deleted expense keeps its row (archived_at) so the sync pull
-- can send its tombstone.
--
-- Indexes: one per list sort and filter (partial on live rows), plus the sync pull keyset index (same name and
-- definition as the SyncKeysetIndex entry, which SyncKeysetIndexJob checks afterwards).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V2__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS expenses (
    id             UUID          PRIMARY KEY,
    store_id       UUID          NOT NULL,
    category       VARCHAR(20)   NOT NULL
                       CONSTRAINT expenses_category_check CHECK (category IN ('goods_purchase', 'rent', 'salaries',
                           'transport', 'utilities', 'telecom', 'marketing', 'taxes', 'maintenance', 'other')),
    amount         NUMERIC(14,2) NOT NULL
                       CONSTRAINT expenses_amount_check CHECK (amount >= 0.01 AND amount <= 999999999999),
    expense_date   DATE          NOT NULL,
    label          VARCHAR(500),
    note           VARCHAR(1000),
    payment_method VARCHAR(20)
                       CONSTRAINT expenses_payment_method_check CHECK (payment_method IS NULL OR payment_method IN
                           ('cash', 'mobile_money', 'bank_transfer', 'card', 'other')),
    created_by     UUID          NOT NULL,
    updated_by     UUID,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    archived_at    TIMESTAMPTZ
);

-- list « recent » / « oldest » of one store (forward or backward scan), date range, totals
CREATE INDEX IF NOT EXISTS idx_expenses_store_date
    ON expenses (store_id, expense_date, created_at, id) WHERE archived_at IS NULL;
-- the same over every store (owner « toutes mes boutiques »)
CREATE INDEX IF NOT EXISTS idx_expenses_date
    ON expenses (expense_date, created_at, id) WHERE archived_at IS NULL;
-- list « amount_desc » / « amount_asc »
CREATE INDEX IF NOT EXISTS idx_expenses_store_amount
    ON expenses (store_id, amount, expense_date, id) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_expenses_amount
    ON expenses (amount, expense_date, id) WHERE archived_at IS NULL;
-- category filter of one store, in the « recent » / « oldest » order (and its totals)
CREATE INDEX IF NOT EXISTS idx_expenses_store_category_date
    ON expenses (store_id, category, expense_date, created_at, id) WHERE archived_at IS NULL;
-- sync pull keyset (ExpenseDeltaProvider): every store (owner), then one store (store member's pull)
CREATE INDEX IF NOT EXISTS idx_expenses_sync_keyset ON expenses (updated_at, (CAST(id AS text)));
CREATE INDEX IF NOT EXISTS idx_expenses_store_sync_keyset ON expenses (store_id, updated_at, (CAST(id AS text)));

-- search « label / note ILIKE '%q%' »: GIN trigram indexes. The operator class comes from the pg_trgm extension
-- installed in public by the public migration V55; this script runs inside the tenant schema without public in its
-- search_path, hence public.gin_trgm_ops. Where the extension is missing, the script installs it in public (trusted
-- extension, same right as V55); only a database refusing it skips the indexes, with a warning (crm V3 pattern).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
        EXCEPTION WHEN insufficient_privilege OR feature_not_supported OR undefined_file
                       OR unique_violation OR duplicate_object THEN -- a concurrent provisioning installed it
            RAISE WARNING 'expenses V1: pg_trgm cannot be installed in %: %', current_schema(), SQLERRM;
        END;
    END IF;
    -- the opclass is named public.gin_trgm_ops: only an extension living in public serves it
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm' AND extnamespace = 'public'::regnamespace) THEN
        CREATE INDEX IF NOT EXISTS idx_expenses_label_trgm ON expenses USING gin (label public.gin_trgm_ops)
            WHERE archived_at IS NULL;
        CREATE INDEX IF NOT EXISTS idx_expenses_note_trgm ON expenses USING gin (note public.gin_trgm_ops)
            WHERE archived_at IS NULL;
    ELSE
        RAISE WARNING 'expenses V1: pg_trgm missing from public, trigram indexes skipped in %', current_schema();
    END IF;
END
$$;
