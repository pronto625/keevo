-- Refresh token rotation: a used token is not deleted but marked as replaced,
-- so a lost refresh response can be recovered and a replay detected.
--   replaced_by_id : the token issued in exchange for this one (NULL until used)
--   rotated_at     : when that exchange happened
-- A revoked token with replaced_by_id NULL was revoked explicitly (logout,
-- password change, deactivation) and is never accepted again.
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS replaced_by_id uuid;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS rotated_at timestamp(6) with time zone;
