-- ─────────────────────────────────────────────────────────────────────────────
-- Module sync — V1 baseline (Story 16.12)
--
-- Offline synchronisation journals of a tenant (idempotency, conflicts, rejected payloads).
-- Tables: sync_operations_log, sync_conflicts_log, sync_error_log.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_sync). It is the single
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

-- ── sync_operations_log ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_operations_log (
    id               VARCHAR(36) PRIMARY KEY,
    operation_type   VARCHAR(50) NOT NULL,
    entity_id        VARCHAR(36),
    status           VARCHAR(20) NOT NULL,
    error_reason     TEXT,
    processed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_timestamp TIMESTAMPTZ
);
-- device_id: last-write-wins tells same-device edits from concurrent ones
ALTER TABLE sync_operations_log ADD COLUMN IF NOT EXISTS entity_id        VARCHAR(36);
ALTER TABLE sync_operations_log ADD COLUMN IF NOT EXISTS error_reason     TEXT;
ALTER TABLE sync_operations_log ADD COLUMN IF NOT EXISTS processed_at     TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE sync_operations_log ADD COLUMN IF NOT EXISTS client_timestamp TIMESTAMPTZ;
ALTER TABLE sync_operations_log ADD COLUMN IF NOT EXISTS device_id        VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_sync_ops_log_entity ON sync_operations_log(entity_id);
CREATE INDEX IF NOT EXISTS idx_sync_ops_log_processed ON sync_operations_log(processed_at);

-- ── sync_conflicts_log ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_conflicts_log (
    id             VARCHAR(36) PRIMARY KEY,
    operation_id   VARCHAR(36) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    entity_id      VARCHAR(36),
    entity_type    VARCHAR(50),
    conflict_type  VARCHAR(30) NOT NULL,
    strategy       VARCHAR(30) NOT NULL,
    conflict_data  JSONB,
    resolved_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id       VARCHAR(36)
);
ALTER TABLE sync_conflicts_log ADD COLUMN IF NOT EXISTS entity_id      VARCHAR(36);
ALTER TABLE sync_conflicts_log ADD COLUMN IF NOT EXISTS entity_type    VARCHAR(50);
ALTER TABLE sync_conflicts_log ADD COLUMN IF NOT EXISTS conflict_data  JSONB;
ALTER TABLE sync_conflicts_log ADD COLUMN IF NOT EXISTS resolved_at    TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE sync_conflicts_log ADD COLUMN IF NOT EXISTS actor_id       VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_sync_conflicts_resolved ON sync_conflicts_log(resolved_at);
CREATE INDEX IF NOT EXISTS idx_sync_conflicts_entity ON sync_conflicts_log(entity_id);

-- ── sync_error_log ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sync_error_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id     VARCHAR(36) NOT NULL,
    operation_type   VARCHAR(50) NOT NULL,
    entity_id        VARCHAR(36),
    payload          JSONB       NOT NULL,
    error_reason     TEXT,
    client_timestamp TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE sync_error_log ADD COLUMN IF NOT EXISTS entity_id        VARCHAR(36);
ALTER TABLE sync_error_log ADD COLUMN IF NOT EXISTS error_reason     TEXT;
ALTER TABLE sync_error_log ADD COLUMN IF NOT EXISTS client_timestamp TIMESTAMPTZ;
ALTER TABLE sync_error_log ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_sync_error_log_created ON sync_error_log(created_at);

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'sync_operations_log'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE sync_operations_log ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'sync_operations_log primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'sync_conflicts_log'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE sync_conflicts_log ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'sync_conflicts_log primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'sync_error_log'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE sync_error_log ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'sync_error_log primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('sync_operations_log', 'processed_at', $d$now()$d$),
                     ('sync_conflicts_log', 'resolved_at', $d$now()$d$),
                     ('sync_error_log', 'id', $d$gen_random_uuid()$d$),
                     ('sync_error_log', 'created_at', $d$now()$d$)) AS d(table_name, column_name, expr)
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
