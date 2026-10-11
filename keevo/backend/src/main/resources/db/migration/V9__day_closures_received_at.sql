-- day_closures.received_at: server time a closure was recorded.
-- An offline closure keeps the time it was made on the device in closed_at, which can be
-- older than other devices' pull cursor; the pull pages on received_at instead.
-- No column default: TenantSchemaSyncService copies this definition into tenant schemas,
-- where existing rows must stay NULL until backfilled from closed_at. The application
-- always writes the column.
ALTER TABLE public.day_closures ADD COLUMN IF NOT EXISTS received_at timestamp(6) with time zone;
UPDATE public.day_closures SET received_at = closed_at WHERE received_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_day_closures_received_at ON public.day_closures (received_at, id);
