-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V2 (Story 14.2): WhatsApp sending of an invoice + seller identity flags of the snapshot.
--
-- Additive only (nullable columns or NOT NULL with a default): an invoice issued under V1 keeps its data
-- (never sent, legal name typed, no logo). Applied lazily inside the tenant schema on the first gated call after
-- the deploy (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V3__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

-- Last WhatsApp sending (re-sending allowed): when, to which number (E.164), how many times.
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_sent_at TIMESTAMPTZ;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS last_sent_to VARCHAR(20);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS send_count INT NOT NULL DEFAULT 0;

-- Seller snapshot flags frozen at issue: the printed name came from the company display name (no legal name
-- typed in the invoicing settings), and a company logo existed when the invoice was issued.
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS seller_name_from_display BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS seller_has_logo BOOLEAN NOT NULL DEFAULT FALSE;
