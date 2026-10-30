-- V31 — Story 14.2: company designation shown on invoices and export documents.
--
-- Optional (nullable), edited by the owner (GET/PUT /api/v1/account/company). Display rule: the designation when
-- filled in, else the tenant name (the shop name typed at onboarding). Public schema only: public.tenants is a
-- GLOBAL_ONLY table (never mirrored into tenant schemas). Read/written by raw JDBC (JdbcCompanyProfileRepository),
-- not mapped in TenantJpaEntity — ddl-auto=validate tolerates the extra column (same as V4). No data written.
ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS designation VARCHAR(150);
