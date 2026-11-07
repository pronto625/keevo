-- crm V3 — Story 20.6 (routed from the 20.2 review): the client list and search at scale.
--
--   * idx_clients_name_lower_id   — btree (lower(name), id): the keyset order of GET /clients?limit= (one index range
--                                   scan per page, never OFFSET);
--   * idx_clients_name_trgm       — GIN trigram on lower(name): search « lower(name) LIKE lower('%q%') »;
--   * idx_clients_phone_trgm      — GIN trigram on phone: search « phone LIKE '%q%' ».
--
-- The trigram operator class comes from the pg_trgm extension installed in public by the public migration V55; this
-- script runs inside the tenant schema without public in its search_path, hence public.gin_trgm_ops. Where the
-- extension is still missing (a database whose public migrations have not run), the script installs it itself in
-- public (trusted extension, same right as V55), so every tenant gets the same shape; only a database refusing the
-- installation skips the two trigram indexes, with a warning, instead of failing the tenant. Idempotent forever, no
-- data touched (db/modules/README.md).
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_clients_name_lower_id ON clients (lower(name), id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
        EXCEPTION WHEN insufficient_privilege OR feature_not_supported OR undefined_file
                       OR unique_violation OR duplicate_object THEN -- a concurrent provisioning installed it
            RAISE WARNING 'crm V3: pg_trgm cannot be installed in %: %', current_schema(), SQLERRM;
        END;
    END IF;
    -- the opclass is named public.gin_trgm_ops: only an extension living in public serves it
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm' AND extnamespace = 'public'::regnamespace) THEN
        CREATE INDEX IF NOT EXISTS idx_clients_name_trgm  ON clients USING gin (lower(name) public.gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_clients_phone_trgm ON clients USING gin (phone public.gin_trgm_ops);
    ELSE
        RAISE WARNING 'crm V3: pg_trgm missing from public, trigram indexes skipped in %', current_schema();
    END IF;
END
$$;
