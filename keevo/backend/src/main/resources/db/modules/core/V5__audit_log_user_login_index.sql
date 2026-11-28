-- ─────────────────────────────────────────────────────────────────────────────
-- Module core — V5 (P02, FUS-NFR11): the sign-ins of a user, newest first.
--
-- GET /auth/profile shows the last sign-in of the user in this company (latest USER_AUTHENTICATED row). On
-- idx_audit_log_user_occurred the action was filtered row by row (every audit row of the user); this partial index holds
-- only the sign-ins, so the latest one is the first entry of the user.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V6__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_audit_log_user_login ON audit_log (user_id, occurred_at DESC)
    WHERE action = 'USER_AUTHENTICATED';
