-- V48 — Story 19.3 (fusion Keevo × Bref, epic 19): Google and Apple sign-in.
--
--   user_authenticators  one row per social identity linked to an account: (provider, subject) is the provider's
--                        stable user id (the ID token's `sub`). A separate table, not columns on users, so one person
--                        can hold Google and Apple at once. Rows go with the account (ON DELETE CASCADE).
--   uq_user_authenticators_provider_subject  sign-in lookup and the ON CONFLICT target of concurrent first logins.
--   ix_user_authenticators_user              cascade delete and per-account listing.
--
-- Additive and idempotent; public schema only.
CREATE TABLE IF NOT EXISTS public.user_authenticators (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    provider   VARCHAR(16)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT user_authenticators_provider_check CHECK (provider IN ('GOOGLE', 'APPLE')),
    CONSTRAINT uq_user_authenticators_provider_subject UNIQUE (provider, subject)
);

CREATE INDEX IF NOT EXISTS ix_user_authenticators_user ON public.user_authenticators (user_id);
