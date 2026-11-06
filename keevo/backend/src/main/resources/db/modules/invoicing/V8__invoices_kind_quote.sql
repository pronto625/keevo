-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V8 (Story 20.3, fusion Keevo × Bref FUS-FR18): quotes (« devis ») live in `invoices`.
--
-- Additive only:
--   * kind                 — 'INVOICE' (every existing row, default) | 'QUOTE';
--   * converted_invoice_id / converted_at — set when a quote is converted (status CONVERTED);
--   * source_quote_id      — on the invoice created by a conversion: the quote it comes from. Unique (partial index):
--                            a concurrent second conversion of the same quote fails instead of making two invoices;
--   * status CHECK         — gains 'CONVERTED';
--   * "issued fields" CHECK — a non-draft row must carry number / issue_date / issued_at, except a quote (a quote
--                            never has any of them, enforced by invoices_quote_check);
--   * index (kind, status, created_at) — the invoice / quote lists filtered by kind.
-- V1 created the status and "issued fields" CHECKs unnamed: they are found in the catalog by their definition and
-- replaced by named ones (the PAID and CANCELLED row CHECKs are kept as they are). Existing invoices satisfy every
-- new constraint (kind = 'INVOICE'), they are still validated in a guarded block. Idempotent: a re-run drops nothing
-- it created (named constraints are skipped) and adds nothing twice.
-- Applied lazily inside the tenant schema (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V9__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS kind                 VARCHAR(10) NOT NULL DEFAULT 'INVOICE';
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS converted_invoice_id UUID;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS converted_at         TIMESTAMPTZ;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS source_quote_id      UUID;

DO $$
DECLARE
    c RECORD;
BEGIN
    -- the unnamed V1 CHECKs replaced below: the status list, and "non-draft ⇒ number, issue_date, issued_at"
    FOR c IN SELECT con.conname FROM pg_constraint con
             WHERE con.conrelid = 'invoices'::regclass AND con.contype = 'c'
               AND con.conname NOT IN ('invoices_status_values_check', 'invoices_issued_fields_check',
                                       'invoices_kind_check', 'invoices_quote_check', 'invoices_converted_check')
               AND (pg_get_constraintdef(con.oid) LIKE '%number IS NOT NULL%'
                    OR (pg_get_constraintdef(con.oid) LIKE '%''ISSUED''%'
                        AND pg_get_constraintdef(con.oid) LIKE '%''CANCELLED''%'
                        AND pg_get_constraintdef(con.oid) LIKE '%''DRAFT''%'))
    LOOP
        EXECUTE format('ALTER TABLE invoices DROP CONSTRAINT %I', c.conname);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_status_values_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_status_values_check
            CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'CANCELLED', 'CONVERTED')) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_issued_fields_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_issued_fields_check
            CHECK (kind = 'QUOTE' OR status = 'DRAFT'
                   OR (number IS NOT NULL AND issue_date IS NOT NULL AND issued_at IS NOT NULL)) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_kind_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_kind_check CHECK (kind IN ('INVOICE', 'QUOTE')) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_quote_check') THEN
        -- a quote is never numbered nor issued, and only ever DRAFT, CONVERTED or CANCELLED
        ALTER TABLE invoices ADD CONSTRAINT invoices_quote_check
            CHECK (kind <> 'QUOTE' OR (number IS NULL AND issue_date IS NULL AND issued_at IS NULL
                                       AND status IN ('DRAFT', 'CONVERTED', 'CANCELLED'))) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_converted_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_converted_check
            CHECK (status <> 'CONVERTED'
                   OR (kind = 'QUOTE' AND converted_invoice_id IS NOT NULL AND converted_at IS NOT NULL)) NOT VALID;
    END IF;

    BEGIN
        ALTER TABLE invoices VALIDATE CONSTRAINT invoices_status_values_check;
        ALTER TABLE invoices VALIDATE CONSTRAINT invoices_issued_fields_check;
        ALTER TABLE invoices VALIDATE CONSTRAINT invoices_kind_check;
        ALTER TABLE invoices VALIDATE CONSTRAINT invoices_quote_check;
        ALTER TABLE invoices VALIDATE CONSTRAINT invoices_converted_check;
    EXCEPTION WHEN check_violation THEN
        RAISE WARNING 'invoices: a legacy row violates a V8 constraint, left NOT VALID (enforced for new writes)';
    END;
END $$;

CREATE INDEX IF NOT EXISTS idx_invoices_kind_status_created ON invoices (kind, status, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_source_quote ON invoices (source_quote_id)
    WHERE source_quote_id IS NOT NULL;
