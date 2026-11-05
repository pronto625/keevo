-- V51 — Story 19.10 (fusion Keevo × Bref, epic 19, FUS-FR31): minimum app versions.
--
--   app_versions   per app identity (kebab-case: keevo, bref…) and platform (ios, android, web): the minimum version
--                  (below it the app must update), the latest version (below it an update is offered), the store link
--                  and the message shown. Read by the public GET /api/v1/app-version/check (one primary-key read),
--                  edited by the super-admin (audited, updated_by / updated_at).
--
-- No seed rows: no row = nothing required, nothing offered. Idempotent (IF NOT EXISTS); public schema only.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.app_versions (
    app_identity   VARCHAR(30)  NOT NULL
                   CONSTRAINT app_versions_app_identity_check CHECK (app_identity ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    platform       VARCHAR(10)  NOT NULL
                   CONSTRAINT app_versions_platform_check CHECK (platform IN ('ios', 'android', 'web')),
    min_version    VARCHAR(20),
    latest_version VARCHAR(20),
    store_url      VARCHAR(500),
    message        VARCHAR(500),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by     UUID,
    PRIMARY KEY (app_identity, platform)
);
