-- V12: Optimistic locking for Sale (Story v1s-13-10)
-- Adds the @Version column so concurrent cancel/correct on the same sale conflict (409 OPTIMISTIC_LOCK)
-- instead of overwriting each other. Per-tenant schemas get the same column via
-- TenantSchemaProvisioner.DDL_SALES_MIGRATE_VERSION (provisioning + boot-time sync).

ALTER TABLE sales ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
