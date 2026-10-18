-- public.sales.updated_at — the column of the delta-pull cursor mapped by SaleJpaEntity.
-- Tenant schemas get it from TenantSchemaProvisioner (DDL_SALES_MIGRATE_UPDATED_AT), but a public
-- schema created before the V1 baseline (prod, ddl-auto era) never had it, and ddl-auto=validate
-- then refuses to boot. Same definition as the tenant DDL; public.sales is a template table.
ALTER TABLE public.sales ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
