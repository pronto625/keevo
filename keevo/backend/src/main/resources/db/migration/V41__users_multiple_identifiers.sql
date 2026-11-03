-- V41 — Story 19.1 (fusion Keevo × Bref, epic 19): public.users accepts phone, email or username as identifier,
-- and carries a small profile (names, verification dates).
--
-- V41 cannot be undone once phone-less rows exist (the V1 2.0.1 backend would NPE on them): never roll the backend
-- back below 19.1 after such rows are written (see docs/DEPLOYMENT_CHECKLIST.md §20).
--
--   phone_number, password_hash  become optional (Bref accounts can be email-only — OTP, Google, Apple — and Bref
--                                members can have a username only). uk_users_phone_number is kept.
--   email, username              new optional identifiers, unique case-insensitively through lower() expression
--                                indexes (uq_products_name precedent, no citext). Lookups must use
--                                lower(col) = lower(?) to hit them. The application stores them stripped, blank as
--                                NULL.
--   first_name, last_name        owner profile (employees keep theirs on the tenant employees table).
--   email_verified_at,
--   phone_verified_at            set by the OTP / social authenticators (19.2, 19.3).
--   users_identifier_present     at least one non-blank identifier among phone, email, username (every existing
--                                row has a phone, so the constraint validates on add).
--   users_username_format        Bref's rule: 1–50 of letters, digits, '.', '_', '-' (no '@', no '+').
--   ix_utm_tenant_role           memberships by (tenant_id, role): owner lookups per tenant.
--
-- Additive and idempotent: no UPDATE, no backfill, existing rows keep NULL in the new columns.
ALTER TABLE public.users ALTER COLUMN phone_number  DROP NOT NULL;
ALTER TABLE public.users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email             VARCHAR(254) NULL;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS username          VARCHAR(50)  NULL;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS first_name        VARCHAR(100) NULL;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS last_name         VARCHAR(100) NULL;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ  NULL;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMPTZ  NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower    ON public.users (lower(email));
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lower ON public.users (lower(username));

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'users_identifier_present' AND conrelid = 'public.users'::regclass) THEN
        ALTER TABLE public.users ADD CONSTRAINT users_identifier_present
            CHECK (nullif(btrim(phone_number), '') IS NOT NULL
                OR nullif(btrim(email), '') IS NOT NULL
                OR nullif(btrim(username), '') IS NOT NULL);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'users_username_format' AND conrelid = 'public.users'::regclass) THEN
        ALTER TABLE public.users ADD CONSTRAINT users_username_format
            CHECK (username IS NULL OR username ~ '^[A-Za-z0-9._-]{1,50}$');
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_utm_tenant_role ON public.user_tenant_memberships (tenant_id, role);
