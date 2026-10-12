-- V15 — Story v1s-18-1: background export jobs.
--
-- public.export_jobs is the column template validated by Hibernate (ddl-auto=validate) and
-- mirrored into tenant schemas by TenantSchemaSyncService; tenant schemas also get the same DDL
-- explicitly from TenantSchemaProvisioner.DDL_EXPORT_JOBS (+ indexes). Rows only ever live in
-- tenant schemas.

CREATE TABLE IF NOT EXISTS public.export_jobs (
    id            UUID         PRIMARY KEY,
    requested_by  UUID         NOT NULL,
    status        VARCHAR(10)  NOT NULL
                    CONSTRAINT export_jobs_status_check
                    CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    format        VARCHAR(10)  NOT NULL
                    CONSTRAINT export_jobs_format_check
                    CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    datasets      VARCHAR(500) NOT NULL,
    store_id      UUID,
    period_from   TIMESTAMPTZ,
    period_to     TIMESTAMPTZ,
    row_count     BIGINT,
    file_name     VARCHAR(255) NOT NULL,
    file_size     BIGINT,
    content_type  VARCHAR(100) NOT NULL,
    storage_path  VARCHAR(500),
    error_code    VARCHAR(50),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_export_jobs_created ON public.export_jobs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_export_jobs_status_expires ON public.export_jobs (status, expires_at);
