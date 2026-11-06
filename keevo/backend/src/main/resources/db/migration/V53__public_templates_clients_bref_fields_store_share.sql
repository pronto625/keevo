-- Story 20.2 — public TEMPLATE tables only: mirrors the columns added to tenant schemas by the module migrations
-- crm V2 (clients: client_type, niu, rccm, address, contact_name, website, birth_date, store_id, phone_normalized,
-- marketing_consent_at, marketing_opt_out_at, marketing_consent_by) and store V3 (stores.share_clients) on
-- public.clients / public.stores, so that Hibernate's ddl-auto: validate (which checks the JPA mapping against the
-- public schema at boot) keeps validating these JPA-mapped columns.
--
-- Tenant schemas do NOT get their columns from here (Story 16.12): they come only from db/modules/crm/V2 (with the
-- indexes) and db/modules/store/V3. Additive, idempotent, metadata-only, no data touched.
DO $$
BEGIN
    IF to_regclass('public.clients') IS NOT NULL THEN
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS client_type          VARCHAR(10);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS niu                  VARCHAR(50);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS rccm                 VARCHAR(50);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS address              VARCHAR(500);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS contact_name         VARCHAR(200);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS website              VARCHAR(255);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS birth_date           DATE;
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS store_id             UUID;
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS phone_normalized     VARCHAR(20);
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS marketing_consent_at TIMESTAMPTZ;
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS marketing_opt_out_at TIMESTAMPTZ;
        ALTER TABLE public.clients ADD COLUMN IF NOT EXISTS marketing_consent_by UUID;
    END IF;
    IF to_regclass('public.stores') IS NOT NULL THEN
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS share_clients BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;
