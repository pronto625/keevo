-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V7 (Story 15.5b): drafts are soft-deleted.
--
-- Additive only: invoices.deleted_at — set when a DRAFT is deleted (DELETE /api/v1/invoices/{id}) instead of removing
-- the row, so the sync pull can tell the other devices (a tombstone `{id, deleted: true}` in the `invoices` delta).
-- A deleted draft is invisible to the API (get, list), can never be issued, and keeps its id (a re-sent creation
-- with that id never resurrects it). Only drafts are ever deleted: issued invoices keep their number for ever.
-- Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V8__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
