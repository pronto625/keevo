-- V20 — Story 9-5 (1/2): system health, platform alerts feed, proactive owner alerts (FR81, FR83).
--
-- All three tables are global (public only): TenantSchemaSyncService lists them as global-only so
-- they are never mirrored into tenant schemas.
--
--   platform_health_samples  one row every 5 minutes (PlatformHealthSampleScheduler, ShedLock):
--                            counters of the last 5 minutes + DB check. Rows older than 90 days purged.
--   admin_alert_acks         « Marquer comme traité » of the admin alerts feed, keyed by the alert's
--                            deterministic key (feed computed on each call, only the ack is stored)
--   sync_silence_notices     one WhatsApp per silence streak (tenant whose devices are silent ≥ 5 days)

CREATE TABLE IF NOT EXISTS public.platform_health_samples (
    sampled_at            TIMESTAMPTZ PRIMARY KEY,
    db_ok                 BOOLEAN     NOT NULL,
    requests_total        BIGINT      NOT NULL DEFAULT 0
                            CONSTRAINT platform_health_samples_requests_check CHECK (requests_total >= 0),
    errors_5xx            BIGINT      NOT NULL DEFAULT 0
                            CONSTRAINT platform_health_samples_errors_check CHECK (errors_5xx >= 0),
    p50_ms                INTEGER,
    p95_ms                INTEGER,
    p99_ms                INTEGER,
    db_active_connections INTEGER
);

CREATE TABLE IF NOT EXISTS public.admin_alert_acks (
    alert_key   VARCHAR(200) PRIMARY KEY,
    handled_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    handled_by  UUID         NOT NULL
);

CREATE TABLE IF NOT EXISTS public.sync_silence_notices (
    tenant_id               UUID        PRIMARY KEY
                              CONSTRAINT fk_sync_silence_notices_tenant
                              REFERENCES public.tenants (id) ON DELETE CASCADE,
    notified_at             TIMESTAMPTZ NOT NULL,
    last_push_at_at_notice  TIMESTAMPTZ NOT NULL
);
