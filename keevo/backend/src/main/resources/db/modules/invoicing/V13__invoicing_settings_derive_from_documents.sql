-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V13 (Story 20.6): the tenant preference « create the clients and products of my documents
-- automatically » (Bref behaviour, FUS-FR16). NULL = not chosen: resolved at read time to true for a tenant whose
-- default sale flow is DOCUMENT, false otherwise. Additive, nullable (metadata-only ADD COLUMN), no data change.
-- Idempotent. Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned).
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V14__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoicing_settings ADD COLUMN IF NOT EXISTS derive_from_documents BOOLEAN;
