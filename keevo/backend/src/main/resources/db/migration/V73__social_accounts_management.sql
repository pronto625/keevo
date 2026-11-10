-- V73 — Story 19.3b (fusion Keevo × Bref, epic 19): social accounts — nonce, link/unlink, Sign in with Apple revocation.
--
--   user_authenticators.revocation_token      Apple refresh token (AES-GCM blob, SecretCipher, context = row id),
--                                             obtained from the authorization code; null for Google and when the
--                                             revocation adapter is disabled.
--   user_authenticators.revocation_client_id  the bundle / Services ID the code was issued for (Apple client_id).
--   social_nonces                             one-time nonces of verified ID tokens (replay refused until expiry);
--                                             purged by AuthTokenCleanupJob on ix_social_nonces_expires.
--   apple_token_revocations                   transactional outbox: Apple tokens to revoke, copied in the same
--                                             transaction that deletes their authenticator (unlink, account
--                                             deletion); drained by AppleTokenRevocationJob on ix_..._due.
--                                             authenticator_id has no FK (the row is gone) — it is the decryption
--                                             context only.
--
-- Additive and idempotent; public schema only.
ALTER TABLE public.user_authenticators ADD COLUMN IF NOT EXISTS revocation_token BYTEA;
ALTER TABLE public.user_authenticators ADD COLUMN IF NOT EXISTS revocation_client_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS public.social_nonces (
    provider   VARCHAR(16)  NOT NULL,
    nonce      VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_social_nonces PRIMARY KEY (provider, nonce)
);

CREATE INDEX IF NOT EXISTS ix_social_nonces_expires ON public.social_nonces (expires_at);

CREATE TABLE IF NOT EXISTS public.apple_token_revocations (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    authenticator_id UUID         NOT NULL,
    client_id        VARCHAR(255) NOT NULL,
    token            BYTEA        NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_apple_token_revocations_due ON public.apple_token_revocations (next_attempt_at);
