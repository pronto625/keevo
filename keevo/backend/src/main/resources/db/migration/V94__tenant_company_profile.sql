-- V94 — company profile of the organisation (owner-edited) and the "setup completed" mark.
--
-- The organisation (public.tenants) carries its identity: description, activity category, address, phone, e-mail,
-- NIU, RCCM, country. All optional (NIU and RCCM are never mandatory), edited by the owner through
-- PATCH /api/v1/account/company/profile. Raw JDBC (JdbcCompanyProfileRepository), not mapped in TenantJpaEntity
-- (ddl-auto=validate tolerates the extra columns, same as V31).
--
-- setup_completed_at: set when the owner finishes the sign-up steps (organisation, first store). A NULL value sends
-- the app back to the first unfinished step. Every tenant that exists when this runs is marked completed: only
-- organisations created afterwards go through the steps.
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS description        VARCHAR(1000);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS category           VARCHAR(40);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS address            VARCHAR(255);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS phone              VARCHAR(30);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS email              VARCHAR(150);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS niu                VARCHAR(30);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS rccm               VARCHAR(50);
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS country            VARCHAR(2) NOT NULL DEFAULT 'CM';
ALTER TABLE public.tenants ADD COLUMN IF NOT EXISTS setup_completed_at TIMESTAMPTZ;

-- Only the first time the column exists: tenants created before this script are already set up.
UPDATE public.tenants SET setup_completed_at = NOW() WHERE setup_completed_at IS NULL AND created_at < NOW();
