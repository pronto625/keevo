-- Story 19.12 — public TEMPLATE tables only: mirrors the columns added to tenant schemas by the module migrations
-- store V2 (stores: category, description, logo_path, niu, rccm, country) and core V3
-- (tenant_preferences.default_sale_flow) on public.stores / public.tenant_preferences, so that Hibernate's
-- ddl-auto: validate (which checks the JPA mapping against the public schema at boot) keeps validating these
-- JPA-mapped columns.
--
-- Tenant schemas do NOT get their columns from here: nothing copies public tables into tenant schemas any more
-- (Story 16.12); they come only from db/modules/store/V2 and db/modules/core/V3. The CHECK on default_sale_flow
-- stays tenant-only. Additive, idempotent, metadata-only (constant defaults), no data touched.
DO $$
BEGIN
    IF to_regclass('public.stores') IS NOT NULL THEN
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS category    VARCHAR(30);
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS logo_path   VARCHAR(255);
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS niu         VARCHAR(30);
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS rccm        VARCHAR(50);
        ALTER TABLE public.stores ADD COLUMN IF NOT EXISTS country     VARCHAR(2) NOT NULL DEFAULT 'CM';
    END IF;
    IF to_regclass('public.tenant_preferences') IS NOT NULL THEN
        ALTER TABLE public.tenant_preferences ADD COLUMN IF NOT EXISTS default_sale_flow VARCHAR(10) NOT NULL
            DEFAULT 'POS';
    END IF;
END $$;
