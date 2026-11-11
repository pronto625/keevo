-- ─────────────────────────────────────────────────────────────────────────────
-- Module core — V4 (Story 19.11c, FUS-NFR11): the merchant rows of the audit log, newest first.
--
-- The super-admin « dernière activité » (TenantLastActivitySql) takes the latest audit row written by a person
-- (never the SYSTEM actor, never a SYSTEM source such as the automatic day closure). On idx_audit_log_occurred the
-- backward walk read every SYSTEM row first (one per store and per day); this partial index holds only the rows the
-- query can return, with the same predicates.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V5__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_audit_log_merchant_occurred ON audit_log (occurred_at DESC)
    WHERE user_id <> '00000000-0000-0000-0000-000000000000'::uuid AND (source IS NULL OR source <> 'SYSTEM');
