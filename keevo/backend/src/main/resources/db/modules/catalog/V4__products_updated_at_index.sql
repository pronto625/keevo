-- ─────────────────────────────────────────────────────────────────────────────
-- Module catalog — V4 (Story 19.11c, FUS-NFR11): the super-admin catalogue order.
--
-- The cross-tenant product list reads, per tenant, the first offset + size products ordered by
-- « updated_at DESC NULLS LAST, id » (AdminCatalogService): without an index each branch sorted its whole products
-- table. This index has exactly that order, so a branch is an index scan stopped after offset + size rows.
-- Foundation script: idempotent forever (executed at registration and again by Flyway). No data change.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V5__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_products_updated_at ON products (updated_at DESC NULLS LAST, id);
