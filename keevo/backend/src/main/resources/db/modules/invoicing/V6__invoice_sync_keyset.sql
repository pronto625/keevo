-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V6 (Story 15.5b): keyset index of the sync pull of invoices.
--
-- Additive only: the `invoices` delta of GET /api/v1/sync/pull (InvoiceDeltaProvider) reads
-- `WHERE updated_at >= :since ORDER BY updated_at, CAST(id AS text)`, paged by keyset; this index lets the planner walk
-- it instead of sorting the table. Same name and definition as the `idx_invoices_sync_keyset` entry of
-- TenantSchemaProvisioner.SYNC_KEYSET_INDEXES, which SyncKeysetIndexJob checks and repairs afterwards.
-- Invoice volumes per tenant are small (hundreds): a plain CREATE INDEX inside the migration is enough.
-- Applied lazily inside the tenant schema on the first gated call, sync operation or bridge delivery after the deploy
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V7__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_invoices_sync_keyset ON invoices (updated_at, (CAST(id AS text)));
