-- ─────────────────────────────────────────────────────────────────────────────
-- Module messaging — V3 notifications retention index (Story 19.11)
--
-- The nightly NotificationRetentionJob deletes, per tenant, the notifications read before now − read-days
-- (keevo.notification-retention.read-days, default 90). This partial index on read_at drives that range delete
-- (unread rows, the bulk of a live inbox, are left out of it).
--
-- IDEMPOTENT FOREVER (see db/modules/README.md): it runs inside the registration transaction and again through Flyway,
-- and on tenants built by any earlier code. It only creates what is missing; it never drops, updates or deletes.
-- MIGRATION POLICY: once released this file is applied — never edit it; a change is a new version (V4__…).
-- ─────────────────────────────────────────────────────────────────────────────

-- never wait long behind a lock held by the running application (fails this tenant, retried next start/request)
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_notifications_read_at
    ON notifications (read_at) WHERE read_at IS NOT NULL;
