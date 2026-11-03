-- ─────────────────────────────────────────────────────────────────────────────
-- Module reporting — V1 baseline (Story 16.12)
--
-- Generated reports and background export jobs of a tenant (the export screens are the paid
-- "exports" licence; the table exists for every tenant).
-- Tables: reports, export_jobs.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_reporting). It is the single
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
-- Never DROP a table or column, never UPDATE/DELETE (except the updated_at backfill of a newly added column, below): no data is changed.
--
-- MIGRATION POLICY: once released this file is applied — never edit it. A change is a new versioned script
-- (V2__…), idempotent as well. validateOnMigrate is on: an edited applied script makes provisioning fail loudly
-- (checksum mismatch) until it is restored.
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start/request)
SET LOCAL lock_timeout = '10s';

-- ── reports ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reports (
    id                UUID         PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    store_id          UUID         NOT NULL,
    store_name        VARCHAR(255),
    actor_id          UUID,
    report_type       VARCHAR(30)  NOT NULL,
    report_date       DATE         NOT NULL,
    content           TEXT         NOT NULL,
    delivery_status   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    delivery_attempts INT          NOT NULL DEFAULT 0,
    last_attempt_at   TIMESTAMPTZ,
    total_revenue     INT          NOT NULL DEFAULT 0,
    total_sales       INT          NOT NULL DEFAULT 0,
    is_automatic      BOOLEAN      NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ
);
-- Story 7.2 actor_id, store_name; Story 7.6 actor_name
ALTER TABLE reports ADD COLUMN IF NOT EXISTS store_name        VARCHAR(255);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS actor_id          UUID;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS delivery_status   VARCHAR(30) NOT NULL DEFAULT 'PENDING'::character varying;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS delivery_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS last_attempt_at   TIMESTAMPTZ;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS total_revenue     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS total_sales       INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS is_automatic      BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS created_at        TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE reports ADD COLUMN IF NOT EXISTS actor_name        VARCHAR(255);
-- updated_at: pull cursor of the reports delta (a report changes after creation: delivery status/attempts). No column
-- default: when the column is added here, its existing rows get updated_at = created_at (the only UPDATE of this
-- script, on the newly added column); the application always writes it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema() AND table_name = 'reports'
                     AND column_name = 'updated_at') THEN
        ALTER TABLE reports ADD COLUMN updated_at TIMESTAMPTZ;
        UPDATE reports SET updated_at = created_at WHERE updated_at IS NULL;
    END IF;
END
$$;
CREATE INDEX IF NOT EXISTS idx_reports_tenant_type ON reports (tenant_id, report_type);
CREATE INDEX IF NOT EXISTS idx_reports_date ON reports (report_date);
CREATE INDEX IF NOT EXISTS idx_reports_delivery_status ON reports (delivery_status, delivery_attempts);
-- idx_reports_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── export_jobs ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS export_jobs (
    id           UUID         PRIMARY KEY,
    requested_by UUID         NOT NULL,
    status       VARCHAR(10)  NOT NULL
                     CONSTRAINT export_jobs_status_check
                     CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    format       VARCHAR(10)  NOT NULL
                     CONSTRAINT export_jobs_format_check
                     CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    datasets     VARCHAR(500) NOT NULL,
    store_id     UUID,
    period_from  TIMESTAMPTZ,
    period_to    TIMESTAMPTZ,
    criteria     TEXT,
    row_count    BIGINT,
    file_name    VARCHAR(255) NOT NULL,
    file_size    BIGINT,
    content_type VARCHAR(100) NOT NULL,
    storage_path VARCHAR(500),
    error_code   VARCHAR(50),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);
-- Story v1s-18-2 criteria
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS store_id     UUID;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS period_from  TIMESTAMPTZ;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS period_to    TIMESTAMPTZ;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS criteria     TEXT;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS row_count    BIGINT;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS file_size    BIGINT;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS storage_path VARCHAR(500);
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS error_code   VARCHAR(50);
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS created_at   TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS started_at   TIMESTAMPTZ;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
ALTER TABLE export_jobs ADD COLUMN IF NOT EXISTS expires_at   TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_export_jobs_created ON export_jobs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_export_jobs_status_expires ON export_jobs (status, expires_at);

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'reports'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE reports ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'reports primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'export_jobs'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE export_jobs ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'export_jobs primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('reports', 'delivery_status', $d$'PENDING'::character varying$d$),
                     ('reports', 'delivery_attempts', $d$0$d$),
                     ('reports', 'total_revenue', $d$0$d$),
                     ('reports', 'total_sales', $d$0$d$),
                     ('reports', 'is_automatic', $d$false$d$),
                     ('reports', 'created_at', $d$now()$d$),
                     ('export_jobs', 'created_at', $d$now()$d$)) AS d(table_name, column_name, expr)
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
