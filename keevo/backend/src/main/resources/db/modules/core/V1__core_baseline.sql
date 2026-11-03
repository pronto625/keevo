-- ─────────────────────────────────────────────────────────────────────────────
-- Module core — V1 baseline (Story 16.12)
--
-- Identity, subscription, roles, preferences, audit trail and employees of a tenant.
-- Tables: users, subscriptions, roles, user_roles, tenant_preferences, audit_log, employees.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_core). It is the single
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

-- ── users ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number    VARCHAR(20)  UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('OWNER','EMPLOYEE')),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- Story v1s-13-12: login lockout columns (failed_attempts, locked_until) on users created before them
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active       BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until    TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at      TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMPTZ NOT NULL DEFAULT now();

-- ── subscriptions ───────────────────────────────────────────────────────────────
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
);
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS plan_type     VARCHAR(20) NOT NULL DEFAULT 'PREMIUM_TRIAL'::character varying;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_stores    INTEGER NOT NULL DEFAULT 2147483647;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_products  INTEGER NOT NULL DEFAULT 2147483647;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_employees INTEGER NOT NULL DEFAULT 2147483647;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'::character varying;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS created_at    TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS expires_at    TIMESTAMPTZ;

-- ── roles ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) UNIQUE NOT NULL,
    permissions JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE roles ADD COLUMN IF NOT EXISTS permissions JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE roles ADD COLUMN IF NOT EXISTS created_at  TIMESTAMPTZ NOT NULL DEFAULT now();

-- ── user_roles ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_roles (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    role_id UUID NOT NULL REFERENCES roles(id),
    UNIQUE (user_id, role_id)
);

-- ── tenant_preferences ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenant_preferences (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sector_type                VARCHAR(30),
    eod_report_time            TIME        NOT NULL DEFAULT '20:00:00',
    stock_alert_enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    eod_report_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    eod_report_channel         VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
    weekly_report_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    weekly_report_day          INTEGER     NOT NULL DEFAULT 0,
    weekly_report_time         TIME        NOT NULL DEFAULT '20:00:00',
    weekly_report_channel      VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
    inventory_report_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    inventory_report_channel   VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
    stock_alert_channel        VARCHAR(20) NOT NULL DEFAULT 'PUSH',
    trend_notification_enabled BOOLEAN     NOT NULL DEFAULT TRUE
);
-- Stories 7.5 (report settings), 8.1 (trend notifications), 17.2 (leaderboard shared with employees, OFF)
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS sector_type                      VARCHAR(30);
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS eod_report_time                  TIME NOT NULL DEFAULT '20:00:00'::time without time zone;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS stock_alert_enabled              BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS created_at                       TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS eod_report_enabled               BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS eod_report_channel               VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'::character varying;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_enabled            BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_day                INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_time               TIME NOT NULL DEFAULT '20:00:00'::time without time zone;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_channel            VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'::character varying;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS inventory_report_enabled         BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS inventory_report_channel         VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'::character varying;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS stock_alert_channel              VARCHAR(20) NOT NULL DEFAULT 'PUSH'::character varying;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS trend_notification_enabled       BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS leaderboard_visible_to_employees BOOLEAN NOT NULL DEFAULT false;

-- ── audit_log ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_log (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    UUID        NOT NULL,
    action       VARCHAR(80) NOT NULL,
    value_before TEXT,
    value_after  TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    store_id     UUID,
    entity_label VARCHAR(200),
    device_id    VARCHAR(100),
    source       VARCHAR(10),
    metadata     JSONB,
    CONSTRAINT audit_log_source_check
        CHECK (source IS NULL OR source IN ('ONLINE', 'SYNC', 'SYSTEM'))
);
-- Story v1s-18-6: enrichment columns on audit trails created before them
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS value_before TEXT;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS value_after  TEXT;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS store_id     UUID;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS entity_label VARCHAR(200);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS device_id    VARCHAR(100);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS source       VARCHAR(10);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS metadata     JSONB;
-- the source column may have been added by the old column sync (no CHECK): add the CHECK once. NOT VALID +
-- guarded VALIDATE: a legacy row outside the list (audit_log is append-only, it can never be fixed) never aborts the
-- upgrade; new rows are checked either way.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'audit_log_source_check' AND conrelid = 'audit_log'::regclass) THEN
        ALTER TABLE audit_log ADD CONSTRAINT audit_log_source_check
            CHECK (source IS NULL OR source IN ('ONLINE', 'SYNC', 'SYSTEM')) NOT VALID;
        BEGIN
            ALTER TABLE audit_log VALIDATE CONSTRAINT audit_log_source_check;
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'audit_log_source_check left NOT VALID in %: legacy rows with another source',
                current_schema();
        END;
    END IF;
END
$$;
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_occurred ON audit_log(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_store_occurred ON audit_log(store_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_user_occurred ON audit_log(user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_action_occurred ON audit_log(action, occurred_at DESC);
-- idx_audit_log_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- append-only at database level (NFR13): tenant-local trigger function, never depends on public
CREATE OR REPLACE FUNCTION keevo_audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $fn$;
CREATE OR REPLACE TRIGGER trg_audit_log_no_mutation
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION keevo_audit_log_immutable();
CREATE OR REPLACE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION keevo_audit_log_immutable();
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;

-- ── employees ───────────────────────────────────────────────────────────────────
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
);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'::character varying;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE employees ADD COLUMN IF NOT EXISTS created_at               TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE employees ADD COLUMN IF NOT EXISTS updated_at               TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_employees_user_id ON employees(user_id);
CREATE INDEX IF NOT EXISTS idx_employees_store_id ON employees(store_id);
-- idx_employees_sync_keyset: owned by the sync keyset index job (built CONCURRENTLY).

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'users'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE users ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'users primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'subscriptions'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE subscriptions ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'subscriptions primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'roles'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE roles ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'roles primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'user_roles'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE user_roles ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'user_roles primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tenant_preferences'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE tenant_preferences ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'tenant_preferences primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'audit_log'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE audit_log ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'audit_log primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'employees'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE employees ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'employees primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('users', 'id', $d$gen_random_uuid()$d$),
                     ('users', 'is_active', $d$true$d$),
                     ('users', 'failed_attempts', $d$0$d$),
                     ('users', 'created_at', $d$now()$d$),
                     ('users', 'updated_at', $d$now()$d$),
                     ('subscriptions', 'id', $d$gen_random_uuid()$d$),
                     ('subscriptions', 'plan_type', $d$'PREMIUM_TRIAL'::character varying$d$),
                     ('subscriptions', 'max_stores', $d$2147483647$d$),
                     ('subscriptions', 'max_products', $d$2147483647$d$),
                     ('subscriptions', 'max_employees', $d$2147483647$d$),
                     ('subscriptions', 'status', $d$'ACTIVE'::character varying$d$),
                     ('subscriptions', 'created_at', $d$now()$d$),
                     ('roles', 'id', $d$gen_random_uuid()$d$),
                     ('roles', 'permissions', $d$'{}'::jsonb$d$),
                     ('roles', 'created_at', $d$now()$d$),
                     ('user_roles', 'id', $d$gen_random_uuid()$d$),
                     ('tenant_preferences', 'id', $d$gen_random_uuid()$d$),
                     ('tenant_preferences', 'eod_report_time', $d$'20:00:00'::time without time zone$d$),
                     ('tenant_preferences', 'stock_alert_enabled', $d$true$d$),
                     ('tenant_preferences', 'created_at', $d$now()$d$),
                     ('tenant_preferences', 'updated_at', $d$now()$d$),
                     ('tenant_preferences', 'eod_report_enabled', $d$true$d$),
                     ('tenant_preferences', 'eod_report_channel', $d$'WHATSAPP'::character varying$d$),
                     ('tenant_preferences', 'weekly_report_enabled', $d$true$d$),
                     ('tenant_preferences', 'weekly_report_day', $d$0$d$),
                     ('tenant_preferences', 'weekly_report_time', $d$'20:00:00'::time without time zone$d$),
                     ('tenant_preferences', 'weekly_report_channel', $d$'WHATSAPP'::character varying$d$),
                     ('tenant_preferences', 'inventory_report_enabled', $d$true$d$),
                     ('tenant_preferences', 'inventory_report_channel', $d$'WHATSAPP'::character varying$d$),
                     ('tenant_preferences', 'stock_alert_channel', $d$'PUSH'::character varying$d$),
                     ('tenant_preferences', 'trend_notification_enabled', $d$true$d$),
                     ('tenant_preferences', 'leaderboard_visible_to_employees', $d$false$d$),
                     ('audit_log', 'id', $d$gen_random_uuid()$d$),
                     ('audit_log', 'occurred_at', $d$now()$d$),
                     ('employees', 'id', $d$gen_random_uuid()$d$),
                     ('employees', 'status', $d$'ACTIVE'::character varying$d$),
                     ('employees', 'password_change_required', $d$true$d$),
                     ('employees', 'created_at', $d$now()$d$),
                     ('employees', 'updated_at', $d$now()$d$)) AS d(table_name, column_name, expr)
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
