-- V68 — Story 21.5 (fusion Keevo × Bref, epic 21, FUS-FR28): the module « WhatsApp Business » (whatsapp-cloud), the
-- owner's own WhatsApp Business account (WABA) connected to Keevo.
--
--   whatsapp_accounts         one connected WhatsApp Business number per company (primary key tenant_id). Meta's single
--                             webhook of the Keevo app is routed to the company by phone_number_id (unique index: one
--                             indexed lookup per delivery for every number it carries) and the template reviews by
--                             waba_id (index). The access token is stored encrypted only (AES-256-GCM, key from the
--                             environment, never in the database: FUS-NFR4). Disconnecting deletes the row (the token
--                             is gone). A deleted company deletes its account (CASCADE); a deleted user keeps the
--                             account of the company (connected_by SET NULL).
--   profile_modules           whatsapp-cloud granted to BUSINESS, the BUSINESS_TRIAL registration trial and the archived
--                             complete plans PREMIUM / PREMIUM_TRIAL (which hold every paid module). The offer is data
--                             (ADR-05): a super-admin may change it. Existing profiles only.
--   tenant_module_activation  whatsapp-cloud owner-disabled for every existing tenant (db/modules/README.md « Modules
--                             introduced after the go-live »): it stays out of their menus until their owner enables it
--                             in « Mes modules ». Tenants created afterwards follow the initial activation rules.
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING: an edited grant or an owner
-- choice is never overwritten); set-based.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.whatsapp_accounts (
    tenant_id            UUID         PRIMARY KEY REFERENCES public.tenants (id) ON DELETE CASCADE,
    waba_id              VARCHAR(64)  NOT NULL CONSTRAINT whatsapp_accounts_waba_check CHECK (waba_id ~ '^[0-9]{1,64}$'),
    phone_number_id      VARCHAR(64)  NOT NULL
                             CONSTRAINT whatsapp_accounts_phone_number_check CHECK (phone_number_id ~ '^[0-9]{1,64}$'),
    display_phone_number VARCHAR(32),
    verified_name        VARCHAR(200),
    access_token_enc     BYTEA        NOT NULL CONSTRAINT whatsapp_accounts_token_check CHECK (length(access_token_enc) > 29),
    connected_by         UUID         REFERENCES public.users (id) ON DELETE SET NULL,
    connected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- the webhook routing (every phone_number_id of one delivery in one = ANY lookup); one company per number
CREATE UNIQUE INDEX IF NOT EXISTS uq_whatsapp_accounts_phone_number_id ON public.whatsapp_accounts (phone_number_id);
-- the template reviews (message_template_status_update is addressed to the WABA)
CREATE INDEX IF NOT EXISTS idx_whatsapp_accounts_waba ON public.whatsapp_accounts (waba_id);
-- the user foreign key (SET NULL on account deletion)
CREATE INDEX IF NOT EXISTS idx_whatsapp_accounts_connected_by ON public.whatsapp_accounts (connected_by);

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, 'whatsapp-cloud', true
FROM public.plan_profiles p
WHERE p.plan_type IN ('BUSINESS', 'BUSINESS_TRIAL', 'PREMIUM', 'PREMIUM_TRIAL')
ON CONFLICT (plan_type, module_id) DO NOTHING;

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'whatsapp-cloud', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
