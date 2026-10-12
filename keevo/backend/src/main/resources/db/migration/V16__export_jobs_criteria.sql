-- V16 — Story v1s-18-2: dataset-specific criteria of background export jobs (e.g. the audit log's
-- employee / action / object type / search filters), as a JSON object of strings.
--
-- public.export_jobs is the Hibernate validation template; tenant schemas get the column from
-- TenantSchemaProvisioner.DDL_EXPORT_JOBS (new tenants) and DDL_EXPORT_JOBS_MIGRATE_CRITERIA
-- (provisioning + TenantSchemaSyncService, existing tenants).

ALTER TABLE public.export_jobs ADD COLUMN IF NOT EXISTS criteria TEXT;
