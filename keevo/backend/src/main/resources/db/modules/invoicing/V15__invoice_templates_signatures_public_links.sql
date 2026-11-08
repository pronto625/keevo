-- ─────────────────────────────────────────────────────────────────────────────
-- Module invoicing — V15 (Story 20.7, fusion Keevo × Bref FUS-FR21/22/23): rendering templates, signatures, public
-- links that last.
--
-- Additive only, no data UPDATE (every existing row reads as before: Keevo standard layout, no signature, link
-- version 0 — the links already sent keep opening):
--   * invoices.invoice_template      — one of Bref's 8 layouts, NULL = the tenant's default (else the Keevo layout);
--   * invoices.ticket_width          — paper of the ticket layouts: '58', '80' (mm) or 'A4', NULL = tenant default;
--   * invoices.signature_client_path / signature_seller_path — file name of the stored signature image (no path);
--   * invoices.public_link_version   — bumped by a rotation: links signed with an older version stop opening;
--   * invoices.legacy_public_token   — the Bref public token of an imported invoice (old links redirected here);
--   * invoices.legacy_reference      — the Bref reference « BRF-XXXXXXXX » printed next to the legal number;
--   * invoicing_document_preferences — one row (id = 1): default template, ticket width, public link lifetime
--     (days, NULL = 30; an imported Bref tenant gets 90). A separate table: writing it never creates the seller
--     settings row (whose absence means « country VAT rate », Story 20.5).
-- Idempotent (IF NOT EXISTS, named constraints guarded). Applied lazily inside the tenant schema
-- (ModuleProvisioner.ensureProvisioned) — never at boot.
--
-- MIGRATION POLICY: once applied, never edit this file — a change is a new V16__… script.
-- ─────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '10s';

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS invoice_template      VARCHAR(20);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS ticket_width          VARCHAR(3);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS signature_client_path VARCHAR(120);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS signature_seller_path VARCHAR(120);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS public_link_version   INT          NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS legacy_public_token   UUID;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS legacy_reference      VARCHAR(12);

CREATE TABLE IF NOT EXISTS invoicing_document_preferences (
    id               SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    invoice_template VARCHAR(20),
    ticket_width     VARCHAR(3),
    public_link_days INT         CHECK (public_link_days IS NULL OR public_link_days BETWEEN 1 AND 365),
    updated_by       UUID,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoices'::regclass
                   AND conname = 'invoices_presentation_check') THEN
        ALTER TABLE invoices ADD CONSTRAINT invoices_presentation_check
            CHECK ((invoice_template IS NULL OR invoice_template IN ('CLASSIC', 'MODERN', 'MINIMAL', 'RECEIPT',
                        'TICKET_CLASSIC', 'TICKET_COMPACT', 'TICKET_DETAILED', 'TICKET_MODERN'))
                   AND (ticket_width IS NULL OR ticket_width IN ('58', '80', 'A4'))
                   AND public_link_version >= 0
                   AND (legacy_reference IS NULL OR legacy_reference ~ '^BRF-[0-9A-F]{8}$'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'invoicing_document_preferences'::regclass
                   AND conname = 'invoicing_document_preferences_values_check') THEN
        ALTER TABLE invoicing_document_preferences ADD CONSTRAINT invoicing_document_preferences_values_check
            CHECK ((invoice_template IS NULL OR invoice_template IN ('CLASSIC', 'MODERN', 'MINIMAL', 'RECEIPT',
                        'TICKET_CLASSIC', 'TICKET_COMPACT', 'TICKET_DETAILED', 'TICKET_MODERN'))
                   AND (ticket_width IS NULL OR ticket_width IN ('58', '80', 'A4')));
    END IF;
END $$;

-- an imported invoice is found by its Bref token (one per tenant; the cross-tenant lookup is public.invoice_legacy_links)
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_legacy_public_token ON invoices (legacy_public_token)
    WHERE legacy_public_token IS NOT NULL;
