-- V70 — Story 21.7 (fusion Keevo × Bref, epic 21, FUS-FR29): the client portal (client-portal), « retrouver mes
-- factures » — an end client signs in with a code sent to his phone and sees every invoice merchants issued to it.
--
--   portal_clients            the end clients (never Keevo users: no password, no company), one per E.164 phone,
--                             created at their first sign-in.
--   portal_sessions           their sessions: only the SHA-256 of the 256-bit bearer token is stored (primary key: one
--                             lookup per request); fixed 30-day lifetime; sign-out deletes the row; at most 10 per
--                             client (oldest deleted at sign-in, index client_id + created_at); expired rows purged
--                             nightly (index expires_at). A deleted client deletes its sessions (CASCADE).
--   invoice_phone_index       one row per issued invoice with a valid client phone: (phone_normalized, tenant_id,
--                             invoice_id) + issued_at, written in the issue transaction (and by the one-shot back-fill
--                             of older invoices, and by the Bref import 24.6). The portal reads it newest first by
--                             phone (keyset index) and reads each document in its own tenant schema. A deleted company
--                             deletes its rows (CASCADE).
--   tenant_module_activation  client-portal owner-disabled for every existing tenant (db/modules/README.md « Modules
--                             introduced after the go-live »): their clients never agreed to a cross-merchant portal;
--                             the owner enables it in « Mes modules ». Tenants created afterwards have it on.
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING); set-based.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.portal_clients (
    id            UUID        PRIMARY KEY,
    phone         VARCHAR(16) NOT NULL CONSTRAINT portal_clients_phone_check CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- the sign-in upsert (one client per phone)
CREATE UNIQUE INDEX IF NOT EXISTS uq_portal_clients_phone ON public.portal_clients (phone);

CREATE TABLE IF NOT EXISTS public.portal_sessions (
    token_hash BYTEA       PRIMARY KEY CONSTRAINT portal_sessions_hash_check CHECK (length(token_hash) = 32),
    client_id  UUID        NOT NULL REFERENCES public.portal_clients (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT portal_sessions_expiry_check CHECK (expires_at > created_at)
);

-- the per-client cap (newest 10 kept) and the foreign key
CREATE INDEX IF NOT EXISTS idx_portal_sessions_client ON public.portal_sessions (client_id, created_at DESC);
-- the nightly purge of expired sessions
CREATE INDEX IF NOT EXISTS idx_portal_sessions_expires_at ON public.portal_sessions (expires_at);

CREATE TABLE IF NOT EXISTS public.invoice_phone_index (
    tenant_id        UUID        NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    invoice_id       UUID        NOT NULL,
    phone_normalized VARCHAR(16) NOT NULL
                         CONSTRAINT invoice_phone_index_phone_check CHECK (phone_normalized ~ '^\+[1-9][0-9]{7,14}$'),
    issued_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, invoice_id)
);

-- the portal listing: one range scan by phone, newest first, keyset on (issued_at, tenant_id, invoice_id)
CREATE INDEX IF NOT EXISTS idx_invoice_phone_index_phone
    ON public.invoice_phone_index (phone_normalized, issued_at DESC, tenant_id DESC, invoice_id DESC);

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'client-portal', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
