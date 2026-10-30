-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V3 (Story 14.4): the sale an automatic invoice was created from (POS → Invoicing bridge).
--
-- Additive only: a nullable column (NULL for every manual invoice and every invoice issued before V3) and a unique
-- index on it — the idempotency key of the bridge: a replayed `sale.completed` can never create a second invoice
-- for the same sale (several NULLs are allowed). No foreign key: invoicing never depends on commerce tables.
-- Applied lazily inside the tenant schema on the first gated call or bridge delivery after the deploy
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V4__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS source_sale_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_source_sale ON invoices (source_sale_id);
