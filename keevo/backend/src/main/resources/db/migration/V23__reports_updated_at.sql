-- reports.updated_at: pull cursor of the reports delta. A report changes after its creation
-- (delivery status and attempts, regeneration); paging on created_at never sent those changes
-- to the other devices.
-- No column default: TenantSchemaSyncService copies this definition into tenant schemas,
-- where existing rows must stay NULL until backfilled from created_at. The application
-- always writes the column (EndOfDayReportJpaEntity).
ALTER TABLE public.reports ADD COLUMN IF NOT EXISTS updated_at timestamp(6) with time zone;
UPDATE public.reports SET updated_at = created_at WHERE updated_at IS NULL;
