-- V14 — Story v1s-18-6: audit log enrichment + database-level immutability (NFR13, item D3).
--
-- public.audit_log is the column template mirrored into tenant schemas by
-- TenantSchemaSyncService; tenant schemas also get the same DDL explicitly from
-- TenantSchemaProvisioner (columns, indexes, trigger). Old rows keep NULL in every new
-- column: no backfill (an UPDATE would violate immutability).
--
-- Immutability: a BEFORE UPDATE/DELETE row trigger and a BEFORE TRUNCATE statement trigger
-- reject every mutation, including from the table owner. REVOKE ... FROM PUBLIC is applied;
-- privileges of the owner role are deliberately not revoked (the trigger is the guard).
-- DROP SCHEMA ... CASCADE (tenant purge) is unaffected by the triggers.

DO $$
BEGIN
    IF to_regclass('public.audit_log') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS store_id     uuid;
    ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS entity_label varchar(200);
    ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS device_id    varchar(100);
    ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS source       varchar(10);
    ALTER TABLE public.audit_log ADD COLUMN IF NOT EXISTS metadata     jsonb;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'audit_log_source_check'
                     AND conrelid = 'public.audit_log'::regclass) THEN
        ALTER TABLE public.audit_log ADD CONSTRAINT audit_log_source_check
            CHECK (source IS NULL OR source IN ('ONLINE', 'SYNC', 'SYSTEM'));
    END IF;

    CREATE INDEX IF NOT EXISTS idx_audit_log_store_occurred  ON public.audit_log (store_id, occurred_at DESC);
    CREATE INDEX IF NOT EXISTS idx_audit_log_user_occurred   ON public.audit_log (user_id, occurred_at DESC);
    CREATE INDEX IF NOT EXISTS idx_audit_log_action_occurred ON public.audit_log (action, occurred_at DESC);
    CREATE INDEX IF NOT EXISTS idx_audit_log_occurred        ON public.audit_log (occurred_at DESC);
END $$;

CREATE OR REPLACE FUNCTION public.keevo_audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

DO $$
BEGIN
    IF to_regclass('public.audit_log') IS NULL THEN
        RETURN;
    END IF;
    CREATE OR REPLACE TRIGGER trg_audit_log_no_mutation
        BEFORE UPDATE OR DELETE ON public.audit_log
        FOR EACH ROW EXECUTE FUNCTION public.keevo_audit_log_immutable();
    CREATE OR REPLACE TRIGGER trg_audit_log_no_truncate
        BEFORE TRUNCATE ON public.audit_log
        FOR EACH STATEMENT EXECUTE FUNCTION public.keevo_audit_log_immutable();
    REVOKE UPDATE, DELETE, TRUNCATE ON public.audit_log FROM PUBLIC;
END $$;
