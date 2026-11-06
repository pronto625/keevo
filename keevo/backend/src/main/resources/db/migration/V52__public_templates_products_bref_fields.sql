-- Story 20.1 — public TEMPLATE table only: mirrors the columns added to tenant schemas by the module migration
-- catalog V2 (products: barcode, product_key, price_manual, merged_into_id) on public.products, so that Hibernate's
-- ddl-auto: validate (which checks the JPA mapping against the public schema at boot) keeps validating these
-- JPA-mapped columns.
--
-- Tenant schemas do NOT get their columns from here (Story 16.12): they come only from db/modules/catalog/V2, with
-- the merged_into_id foreign key and the partial indexes. Additive, idempotent, metadata-only, no data touched.
DO $$
BEGIN
    IF to_regclass('public.products') IS NOT NULL THEN
        ALTER TABLE public.products ADD COLUMN IF NOT EXISTS barcode        VARCHAR(100);
        ALTER TABLE public.products ADD COLUMN IF NOT EXISTS product_key    VARCHAR(250);
        ALTER TABLE public.products ADD COLUMN IF NOT EXISTS price_manual   BOOLEAN NOT NULL DEFAULT false;
        ALTER TABLE public.products ADD COLUMN IF NOT EXISTS merged_into_id UUID;
    END IF;
END $$;
