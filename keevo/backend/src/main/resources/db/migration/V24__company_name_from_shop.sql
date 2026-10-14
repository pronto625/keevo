-- Companies registered before the onboarding fix kept their technical code as
-- name (« KV-2AOYRK » shown as company name in the tenant picker, PDF headers
-- and admin). Take the name of their first store, unless it is still the
-- registration placeholder. A schema that cannot be read is skipped.
DO $$
DECLARE
    t record;
    shop text;
BEGIN
    FOR t IN
        SELECT id, schema_name FROM public.tenants
        WHERE name = code AND schema_name ~ '^kv_[a-z0-9_]{1,59}$'
    LOOP
        BEGIN
            EXECUTE format('SELECT name FROM %I.stores ORDER BY created_at ASC LIMIT 1', t.schema_name)
                INTO shop;
            IF shop IS NOT NULL AND btrim(shop) <> '' AND shop <> 'Ma Boutique' THEN
                UPDATE public.tenants SET name = shop WHERE id = t.id;
            END IF;
        EXCEPTION WHEN undefined_table OR invalid_schema_name THEN
            NULL;
        END;
    END LOOP;
END $$;
