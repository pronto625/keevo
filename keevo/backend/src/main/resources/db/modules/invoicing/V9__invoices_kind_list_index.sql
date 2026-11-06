-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V9 (Story 20.3): the index of the invoice / quote lists.
--
-- GET /api/v1/invoices (JdbcInvoiceRepository.list) reads `WHERE kind = ? AND deleted_at IS NULL
-- ORDER BY created_at DESC, id DESC` (default: invoices only; ?kind=QUOTE: quotes only): this partial index serves the
-- filter and the order without a sort (V8's (kind, status, created_at) only serves a list filtered by status too).
-- Additive and idempotent. Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V10__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_invoices_kind_created ON invoices (kind, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
