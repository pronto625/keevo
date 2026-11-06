-- crm V2 — Story 20.2 (fusion Keevo × Bref, FUS-FR15 / ADR-08): a client carries what a Bref customer carries, belongs
-- to a store, and has a traced marketing consent.
--
--   * client_type          — 'PERSON' | 'COMPANY' (Java enum ClientType, no CHECK: the list may grow), NULL = unknown;
--   * niu, rccm            — tax number / trade register number (companies);
--   * address, contact_name, website, birth_date — Bref profile fields;
--   * store_id             — the store owning the client (ADR-08); NULL = legacy client created before 20.2, visible
--                            from every store. New clients always get a store (server-assigned). No foreign key: the
--                            README rule allows one only together with its table, and stores are never deleted;
--   * phone_normalized     — E.164 form of the phone (PhoneNumbers.toE164), for the same-store duplicate warning; NOT
--                            unique (a duplicate is a warning, never a constraint). Existing rows are filled by the
--                            one-shot boot job ClientPhoneBackfillJob (module scripts never UPDATE data);
--   * marketing_consent_at / marketing_consent_by — consent given by the team (cleared on withdrawal);
--   * marketing_opt_out_at — the client's own STOP (21.5); never written nor cleared by a team consent.
--
-- Every column is nullable: ADD COLUMN is metadata-only (no table rewrite). Idempotent forever (run inside
-- registration and by Flyway on every tenant, see db/modules/README.md). No data touched.
SET LOCAL lock_timeout = '10s';

ALTER TABLE clients ADD COLUMN IF NOT EXISTS client_type          VARCHAR(10);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS niu                  VARCHAR(50);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS rccm                 VARCHAR(50);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS address              VARCHAR(500);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS contact_name         VARCHAR(200);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS website              VARCHAR(255);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS birth_date           DATE;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS store_id             UUID;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS phone_normalized     VARCHAR(20);
ALTER TABLE clients ADD COLUMN IF NOT EXISTS marketing_consent_at TIMESTAMPTZ;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS marketing_opt_out_at TIMESTAMPTZ;
ALTER TABLE clients ADD COLUMN IF NOT EXISTS marketing_consent_by UUID;

-- duplicate probe (same store, same normalized phone) and per-store listing / pull filter
CREATE INDEX IF NOT EXISTS idx_clients_store_phone ON clients(store_id, phone_normalized);
CREATE INDEX IF NOT EXISTS idx_clients_store       ON clients(store_id);
