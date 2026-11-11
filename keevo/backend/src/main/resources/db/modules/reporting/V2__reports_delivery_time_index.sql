-- ─────────────────────────────────────────────────────────────────────────────
-- Module reporting — V2 (Story 19.11c, FUS-NFR11): reports by delivery status and attempt time.
--
-- The super-admin adoption probe (SENT reports in the last 30 days) and the alerts feed (FAILED reports since a date)
-- filter on « delivery_status = ? AND COALESCE(last_attempt_at, created_at) >= ? »: one index range scan each.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V3__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_reports_delivery_attempt
    ON reports (delivery_status, (COALESCE(last_attempt_at, created_at)));
