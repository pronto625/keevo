-- V45 — Story 19.2 (fusion Keevo × Bref, epic 19): one-time sign-in codes (SMS, WhatsApp, email).
--
--   otp_challenges  one row per code sent to an identifier (E.164 phone or lowercased email). Only a bcrypt hash of
--                   the code is stored. A row is live while consumed_at is null and expires_at is in the future;
--                   a new request consumes the identifier's live rows. attempts counts wrong codes (5 = dead) and the
--                   sum over the last 24 h is the daily failure cap. Rows older than 24 h are purged by
--                   AuthTokenCleanupJob.
--   ix_otp_challenges_identifier_created  latest live challenge (verify, FOR UPDATE … LIMIT 1) and the 24 h sum.
--   ix_otp_challenges_created             daily purge (DELETE … WHERE created_at < ?).
--
-- Additive and idempotent; public schema only.
CREATE TABLE IF NOT EXISTS public.otp_challenges (
    id          UUID         PRIMARY KEY,
    identifier  VARCHAR(254) NOT NULL,
    code_hash   VARCHAR(100) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ  NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_otp_challenges_identifier_created
    ON public.otp_challenges (identifier, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_otp_challenges_created ON public.otp_challenges (created_at);
