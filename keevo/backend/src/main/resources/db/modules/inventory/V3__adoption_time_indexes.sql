-- ─────────────────────────────────────────────────────────────────────────────
-- Module inventory — V3 (Story 19.11c, FUS-NFR11): time indexes of the super-admin adoption probes.
--
-- The adoption screen asks, per tenant, whether an inventory session started / a stock transfer occurred in the last
-- 30 days (EXISTS … WHERE started_at / occurred_at in a window). The existing indexes lead with the store or the
-- product, so each probe scanned the table; these make it one index range probe.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V4__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_inventory_sessions_started_at ON inventory_sessions (started_at);
CREATE INDEX IF NOT EXISTS idx_stock_transfers_occurred_at ON stock_transfers (occurred_at);
