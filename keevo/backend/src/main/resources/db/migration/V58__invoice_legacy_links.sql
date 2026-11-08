-- V58 — Story 20.7 (fusion Keevo × Bref, FUS-FR23): the old Bref public invoice links keep working.
--
--   invoice_legacy_links  one row per imported Bref invoice that had a public token: an old link
--                         (« …/v1/invoices/public/<uuid> », redirected to Keevo) carries only that UUID, so the tenant
--                         holding the invoice is found here in one primary-key read (no scan of the tenant schemas).
--                         expires_at = the Bref expiry carried over by the import (NULL = no expiry of its own; the
--                         tenant must still be ACTIVE). A rotation of the invoice's link deletes its row. Rows of a
--                         deleted tenant are removed by the account deletion (TenantDeletionService).
--   ix_invoice_legacy_links_tenant  the account deletion's DELETE … WHERE tenant_schema = ?.
--
-- Written by the invoicing module only (import port, Story 24.4; rotation, Story 20.7). Additive and idempotent;
-- public schema only.
CREATE TABLE IF NOT EXISTS public.invoice_legacy_links (
    token         UUID        PRIMARY KEY,
    tenant_schema VARCHAR(63) NOT NULL,
    invoice_id    UUID        NOT NULL,
    expires_at    TIMESTAMPTZ NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_invoice_legacy_links_tenant ON public.invoice_legacy_links (tenant_schema);
