-- ─────────────────────────────────────────────────────────────────────────────
-- Module messaging — V1 baseline (Story 16.12)
--
-- Owner notifications of a tenant: draft-product notices, push device tokens, alert cooldowns.
-- Tables: draft_notifications, device_tokens, notification_cooldowns.
-- Runs inside the tenant schema (search_path = the tenant schema; history flyway_history_messaging). It is the single
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

-- ── draft_notifications ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS draft_notifications (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id   UUID         NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    actor_id     UUID         NOT NULL,
    tenant_id    UUID         NOT NULL,
    acknowledged BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
ALTER TABLE draft_notifications ADD COLUMN IF NOT EXISTS acknowledged BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE draft_notifications ADD COLUMN IF NOT EXISTS created_at   TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_draft_notif_product ON draft_notifications(product_id);
CREATE INDEX IF NOT EXISTS idx_draft_notif_pending ON draft_notifications(acknowledged) WHERE acknowledged = FALSE;

-- ── device_tokens ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_tokens (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    token       VARCHAR(512) NOT NULL,
    platform    VARCHAR(20)  NOT NULL,
    device_name VARCHAR(100),
    role        VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_device_tokens_token UNIQUE (token)
);
ALTER TABLE device_tokens ADD COLUMN IF NOT EXISTS device_name VARCHAR(100);
ALTER TABLE device_tokens ADD COLUMN IF NOT EXISTS created_at  TIMESTAMP NOT NULL DEFAULT now();
ALTER TABLE device_tokens ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMP NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_device_tokens_role ON device_tokens(role);

-- ── notification_cooldowns ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notification_cooldowns (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cooldown_type VARCHAR(30) NOT NULL,
    product_id    UUID,
    store_id      UUID,
    last_sent_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_cooldown_product_store_type UNIQUE (cooldown_type, product_id, store_id)
);
ALTER TABLE notification_cooldowns ADD COLUMN IF NOT EXISTS product_id    UUID;
ALTER TABLE notification_cooldowns ADD COLUMN IF NOT EXISTS store_id      UUID;
CREATE INDEX IF NOT EXISTS idx_cooldown_type_store ON notification_cooldowns(cooldown_type, store_id);

-- ── primary keys and defaults of tables that drifted (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'draft_notifications'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE draft_notifications ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'draft_notifications primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'device_tokens'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE device_tokens ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'device_tokens primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'notification_cooldowns'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE notification_cooldowns ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'notification_cooldowns primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.table_name, c.column_name, d.expr
        FROM (VALUES ('draft_notifications', 'id', $d$gen_random_uuid()$d$),
                     ('draft_notifications', 'acknowledged', $d$false$d$),
                     ('draft_notifications', 'created_at', $d$now()$d$),
                     ('device_tokens', 'created_at', $d$now()$d$),
                     ('device_tokens', 'updated_at', $d$now()$d$),
                     ('notification_cooldowns', 'id', $d$gen_random_uuid()$d$)) AS d(table_name, column_name, expr)
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
