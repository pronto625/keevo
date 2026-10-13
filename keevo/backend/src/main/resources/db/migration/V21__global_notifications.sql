-- V21 — Story 9-5 (2/2): global push notifications sent by the super admin (FR82).
--
-- Global (public only): TenantSchemaSyncService lists the table as global-only so it is never mirrored
-- into tenant schemas. One row per announcement; GlobalNotificationScheduler claims the due SCHEDULED
-- rows every minute (atomic UPDATE … RETURNING + ShedLock), sends them via FCM and stores the counts.
-- Rows older than 90 days (except still SCHEDULED) are purged daily (GlobalNotificationPurgeJob).

CREATE TABLE IF NOT EXISTS public.global_notifications (
    id               UUID         PRIMARY KEY,
    title            VARCHAR(80)  NOT NULL,
    body             VARCHAR(200) NOT NULL,
    target           VARCHAR(20)  NOT NULL
                       CONSTRAINT global_notifications_target_check
                       CHECK (target IN ('ALL', 'PREMIUM', 'TRIAL', 'FREE', 'TENANT')),
    target_tenant_id UUID,
    scheduled_at     TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(10)  NOT NULL
                       CONSTRAINT global_notifications_status_check
                       CHECK (status IN ('SCHEDULED', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
    recipient_count  INTEGER,
    delivered_count  INTEGER,
    failure_count    INTEGER,
    -- why a FAILED row failed (null otherwise)
    failure_reason   VARCHAR(30)
                       CONSTRAINT global_notifications_failure_reason_check
                       CHECK (failure_reason IN ('PUSH_UNAVAILABLE', 'ALL_CHUNKS_FAILED', 'INTERRUPTED', 'NO_RECIPIENT')),
    created_by       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- when the dispatcher moved the row to SENDING (rows SENDING > 15 min are FAILED / INTERRUPTED)
    claimed_at       TIMESTAMPTZ,
    -- end of the send, set on every terminal state (SENT, FAILED, CANCELLED)
    sent_at          TIMESTAMPTZ,
    -- the target company is required for TENANT, forbidden otherwise
    CONSTRAINT global_notifications_target_tenant_check
        CHECK ((target = 'TENANT') = (target_tenant_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_global_notifications_status_scheduled
    ON public.global_notifications (status, scheduled_at);

CREATE INDEX IF NOT EXISTS idx_global_notifications_created_at
    ON public.global_notifications (created_at);
