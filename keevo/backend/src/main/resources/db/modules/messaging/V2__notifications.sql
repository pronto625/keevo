-- ─────────────────────────────────────────────────────────────────────────────
-- Module messaging — V2 notifications inbox (Story 19.9)
--
-- Persistent in-app notifications of a tenant: one row per recipient user, written by PersistingPushNotifier before
-- the push, read by GET /api/v1/notifications (own rows, newest first) and its unread count.
-- Table: notifications.
--
-- IDEMPOTENT FOREVER (see db/modules/README.md): it runs inside the registration transaction and again through Flyway,
-- and on tenants built by any earlier code. It only creates what is missing (table, column, index, primary key,
-- default); it never drops, updates or deletes.
-- MIGRATION POLICY: once released this file is applied — never edit it; a change is a new version (V3__…).
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start/request)
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS notifications (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID          NOT NULL,
    type       VARCHAR(64)   NOT NULL,
    title      VARCHAR(150)  NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    data       JSONB,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- every column of a drifted, pre-existing copy (no-op on an up-to-date tenant). id and created_at get today's default
-- (filled on existing rows); user_id, type, title and body have no safe default, so a missing one is added NULLABLE
-- (an existing row is never given an invented owner or text; the application always writes them).
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS id         UUID DEFAULT gen_random_uuid();
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS user_id    UUID;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS type       VARCHAR(64);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS title      VARCHAR(150);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS body       VARCHAR(1000);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS data       JSONB;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at    TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- list of one user, newest first (keyed tie-break on id)
CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON notifications (user_id, created_at DESC, id DESC);
-- unread count of one user
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications (user_id) WHERE read_at IS NULL;

-- ── primary key and defaults of a drifted copy (guarded: no statement when already right) ──
DO $$
DECLARE
    col RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'notifications'::regclass AND contype = 'p') THEN
        BEGIN
            ALTER TABLE notifications ADD PRIMARY KEY (id);
        EXCEPTION WHEN unique_violation OR not_null_violation THEN
            RAISE WARNING 'notifications primary key not added in %: duplicate or NULL keys already present', current_schema();
        END;
    END IF;

    FOR col IN
        SELECT c.column_name, d.expr
        FROM (VALUES ('id', $d$gen_random_uuid()$d$),
                     ('created_at', $d$now()$d$)) AS d(column_name, expr)
        JOIN information_schema.columns c
          ON c.table_schema = current_schema()
         AND c.table_name = 'notifications'
         AND c.column_name = d.column_name
        WHERE c.column_default IS NULL
    LOOP
        EXECUTE format('ALTER TABLE notifications ALTER COLUMN %I SET DEFAULT ' || col.expr, col.column_name);
    END LOOP;
END
$$;
