-- ─────────────────────────────────────────────────────────────────────────────
-- Module pos — V3 (Story 19.11c, FUS-NFR11): index of a sale's effective time.
--
-- The super-admin « dernière activité » of a tenant (TenantLastActivitySql) reads MAX(COALESCE(occurred_at,
-- created_at)) over every sale, and the adoption screen probes a COALESCE(occurred_at, created_at) window: both were a
-- scan of the whole sales table per tenant. With this expression index the maximum is one index probe and the window
-- an index range scan.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V4__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_sales_effective_at ON sales ((COALESCE(occurred_at, created_at)));
