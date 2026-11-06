-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V11 (Story 20.5, fusion Keevo × Bref FUS-FR19): discount, delivery fee, per-document VAT rate or
-- exemption, due date, copied client data, line product and reference.
--
-- Additive only, no data UPDATE (every existing row reads as before: no discount, no delivery, its stored rate):
--   * invoices.discount_amount   — whole XAF taken off the subtotal before VAT (0 by default, ≤ subtotal);
--   * invoices.delivery_fee      — whole XAF added after VAT, outside the VAT base (0 by default);
--   * invoices.subtotal          — Σ line amounts as entered (HT or TTC per prices_include_vat), before the discount;
--                                  NULL on rows written before V11 (read back as total HT or total TTC);
--   * invoices.vat_rate_override — the rate requested for the document (percent, 0–100), NULL = the seller's rate;
--                                  invoices.vat_rate keeps holding the rate applied;
--   * invoices.vat_exempt        — no VAT on the whole document (« Exonéré de TVA »);
--   * invoices.due_date          — the date the client must have paid (overdue job);
--   * invoices.client_rccm / client_email / client_contact_name / client_website — client data copied on the document;
--   * invoice_items.product_id   — the catalogue product of the line (no cross-module foreign key);
--   * invoice_items.reference    — the line's printed reference;
--   * idx_invoices_issued_due_date — the overdue job's scan of issued invoices with a due date.
-- Idempotent (IF NOT EXISTS, named constraints guarded). Applied lazily inside the tenant schema
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V12__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS discount_amount     BIGINT       NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS delivery_fee        BIGINT       NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS subtotal            BIGINT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS vat_rate_override   NUMERIC(5,2);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS vat_exempt          BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS due_date            DATE;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS client_rccm         VARCHAR(50);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS client_email        VARCHAR(255);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS client_contact_name VARCHAR(120);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS client_website      VARCHAR(200);

ALTER TABLE invoice_items ADD COLUMN IF NOT EXISTS product_id UUID;
ALTER TABLE invoice_items ADD COLUMN IF NOT EXISTS reference  VARCHAR(100);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_discount_delivery_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_discount_delivery_check
            CHECK (discount_amount >= 0 AND delivery_fee >= 0
                   AND (subtotal IS NULL OR (subtotal >= 0 AND discount_amount <= subtotal)));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_vat_rate_override_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_vat_rate_override_check
            CHECK (vat_rate_override IS NULL OR (vat_rate_override >= 0 AND vat_rate_override <= 100));
    END IF;
END $$;

-- overdue job (JdbcInvoicePaymentRepository.markOverdue): issued invoices past their own due date
CREATE INDEX IF NOT EXISTS idx_invoices_issued_due_date ON invoices (due_date)
    WHERE kind = 'INVOICE' AND status = 'ISSUED' AND due_date IS NOT NULL AND deleted_at IS NULL;
