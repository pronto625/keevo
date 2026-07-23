-- V6__password_reset_tokens.sql
-- Story 14.12: Réinitialisation mot de passe oublié via OTP WhatsApp
-- Public schema only — no per-tenant migration needed.

CREATE TABLE IF NOT EXISTS public.password_reset_tokens (
    id              UUID                        NOT NULL,
    user_id         UUID                        NOT NULL REFERENCES public.users(id),
    phone_number    VARCHAR(20)                 NOT NULL,
    code_hash       VARCHAR(255)                NOT NULL,
    expires_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP(6) WITH TIME ZONE,
    attempts        INT                         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,  -- required by JpaBaseEntity (D8)
    PRIMARY KEY (id)
);

-- Index for lookup by phone_number (most recent active token)
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_phone_created
    ON public.password_reset_tokens (phone_number, created_at DESC);

-- Index for invalidating all active tokens for a user
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id
    ON public.password_reset_tokens (user_id);
