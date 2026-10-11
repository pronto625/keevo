-- V10__auth_token_hygiene.sql — Story 12.12 (auth & session hardening)
-- Public schema only.

-- ── password_reset_tokens ────────────────────────────────────────────────────
-- Active-token lookup (findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc)
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_phone_active
    ON public.password_reset_tokens (phone_number, created_at DESC)
    WHERE consumed_at IS NULL;

-- Daily purge (AuthTokenCleanupJob)
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at
    ON public.password_reset_tokens (expires_at);

-- ── refresh_tokens ───────────────────────────────────────────────────────────
-- revokeAllByUserId (logout everywhere, password change / reset, deactivation)
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id
    ON public.refresh_tokens (user_id);

-- Rotation chain lookups (replay detection / lost-response recovery)
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_replaced_by_id
    ON public.refresh_tokens (replaced_by_id)
    WHERE replaced_by_id IS NOT NULL;

-- Daily purge (AuthTokenCleanupJob)
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON public.refresh_tokens (expires_at);

-- ── Phone normalisation (canonical form '+<digits>', what the app sends) ─────
-- Non-destructive: a user whose '+' form already exists is SKIPPED (no unique
-- violation on uk_users_phone_number); such a pair needs a manual merge.
-- Only all-digit values short enough to fit varchar(20) once prefixed are touched.
UPDATE public.users u
   SET phone_number = '+' || u.phone_number
 WHERE u.phone_number ~ '^[0-9]+$'
   AND length(u.phone_number) < 20
   AND NOT EXISTS (
         SELECT 1 FROM public.users o
          WHERE o.phone_number = '+' || u.phone_number
       );

UPDATE public.password_reset_tokens t
   SET phone_number = '+' || t.phone_number
 WHERE t.phone_number ~ '^[0-9]+$'
   AND length(t.phone_number) < 20;
