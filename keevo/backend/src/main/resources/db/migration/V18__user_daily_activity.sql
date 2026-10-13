-- V18 — Story 9-4: platform analytics (DAU/MAU, cohort retention, feature adoption).
--
-- One row per (user, tenant, WAT calendar day) on which the user made at least one authenticated
-- API call. Written asynchronously by UserActivityRecorder (INSERT … ON CONFLICT DO NOTHING, at most
-- once per key per day thanks to an in-memory dedupe). SUPER_ADMIN calls are never recorded.
-- Rows older than 760 days are purged daily by UserActivityPurgeJob (ShedLock).
--
-- Global-only table: TenantSchemaSyncService lists it as global so it is never mirrored into
-- tenant schemas. No FK to public.tenants on purpose: the insert runs off the request thread and
-- must stay as cheap as possible; tenant rows are never hard-deleted (status DELETED).

CREATE TABLE IF NOT EXISTS public.user_daily_activity (
    user_id        UUID         NOT NULL,
    tenant_id      UUID         NOT NULL,
    activity_date  DATE         NOT NULL,
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_daily_activity PRIMARY KEY (user_id, tenant_id, activity_date)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_date
    ON public.user_daily_activity (activity_date);

-- Retention / adoption read "which tenants were active in a window".
CREATE INDEX IF NOT EXISTS idx_user_daily_activity_tenant_date
    ON public.user_daily_activity (tenant_id, activity_date);
