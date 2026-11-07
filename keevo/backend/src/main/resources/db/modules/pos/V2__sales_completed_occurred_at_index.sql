-- ─────────────────────────────────────────────────────────────────────────────
-- Module pos — V2 (Story 20.9, R4): index of the all-stores revenue windows.
--
-- The reporting revenue source reads COMPLETED sales filtered on occurred_at only for the all-stores figures (owner
-- dashboard totals and charts, seller ranking, profitability without a store); the existing (store_id, occurred_at)
-- index only serves store-scoped windows. Partial: only the sales that count in revenue.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V3__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_sales_completed_occurred_at ON sales (occurred_at) WHERE status = 'COMPLETED';
