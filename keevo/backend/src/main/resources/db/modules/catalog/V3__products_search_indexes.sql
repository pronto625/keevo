-- catalog V3 — Story 21.8 (FUS-NFR11, AI tool search_products): the product search is one bounded query instead of
-- a load of the whole catalogue.
--
--   * idx_products_active_name    — btree (lower(name), id) on non-archived products: the first page by name without
--                                   search (ORDER BY lower(name), id LIMIT n, one index range scan);
--   * idx_products_name_trgm      — GIN trigram on lower(name): « lower(name) LIKE '%q%' »;
--   * idx_products_sku_trgm       — GIN trigram on lower(sku);
--   * idx_products_barcode_trgm   — GIN trigram on lower(barcode).
--
-- Same shape as crm V3: the trigram operator class comes from pg_trgm in public (public migration V55), installed here
-- if still missing; a database refusing the installation skips the trigram indexes with a warning instead of failing
-- the tenant. Idempotent forever, no data touched (db/modules/README.md).
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_products_active_name ON products (lower(name), id) WHERE archived = false;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
        EXCEPTION WHEN insufficient_privilege OR feature_not_supported OR undefined_file
                       OR unique_violation OR duplicate_object THEN -- a concurrent provisioning installed it
            RAISE WARNING 'catalog V3: pg_trgm cannot be installed in %: %', current_schema(), SQLERRM;
        END;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm' AND extnamespace = 'public'::regnamespace) THEN
        CREATE INDEX IF NOT EXISTS idx_products_name_trgm    ON products USING gin (lower(name) public.gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_products_sku_trgm     ON products USING gin (lower(sku) public.gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_products_barcode_trgm ON products USING gin (lower(barcode) public.gin_trgm_ops);
    ELSE
        RAISE WARNING 'catalog V3: pg_trgm missing from public, trigram indexes skipped in %', current_schema();
    END IF;
END
$$;
