-- core V2 — Story 19.7a (plans are data, ADR-05): subscriptions.plan_type accepts any plan code of the catalogue
-- (public.plans), not only FREE / PREMIUM_TRIAL / PREMIUM.
--
--   * the CHECK on plan_type is dropped: V1 created it unnamed (PostgreSQL named it subscriptions_plan_type_check);
--     tenants built by earlier code may carry it under another name, so every CHECK of this table that reads
--     plan_type is dropped (status keeps its own CHECK). No foreign key: public.plans lives in another schema and a
--     tenant table never depends on public DDL; codes are only ever written from the catalogue;
--   * the column is widened to VARCHAR(30), the catalogue's code length (binary-coercible: no rewrite);
--   * the default ('PREMIUM_TRIAL') is kept: registration always seeds the plan explicitly.
--
-- Idempotent forever (run inside registration and by Flyway on every tenant, see db/modules/README.md). No data
-- touched.
SET LOCAL lock_timeout = '10s';

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_plan_type_check;

DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN SELECT con.conname FROM pg_constraint con
             WHERE con.conrelid = 'subscriptions'::regclass AND con.contype = 'c'
               AND pg_get_constraintdef(con.oid) LIKE '%plan_type%'
    LOOP
        EXECUTE format('ALTER TABLE subscriptions DROP CONSTRAINT %I', c.conname);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_attribute a
               WHERE a.attrelid = 'subscriptions'::regclass AND a.attname = 'plan_type' AND NOT a.attisdropped
                 AND a.atttypmod - 4 < 30) THEN
        ALTER TABLE subscriptions ALTER COLUMN plan_type TYPE VARCHAR(30);
    END IF;
END $$;
