-- Story 24.4 — the free-text notes of a document imported from Bref (invoice.notes), kept verbatim so the import
-- loses nothing (R3). No Keevo field shows them yet: the column is read by support / reconciliation only. Nullable, no
-- default, no index (never filtered). Idempotent (module script, README rules).
SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS legacy_notes TEXT;
