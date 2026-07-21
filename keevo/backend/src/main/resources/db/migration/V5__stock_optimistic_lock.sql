-- V5: Optimistic locking for StockTransfer + StockLevel (Story v1s-13-1)
-- Adds @Version column to prevent concurrent mutation corruption (B-CRIT-1/2)
-- Per-tenant schemas get the same columns via TenantSchemaProvisioner DDL migration constants.

ALTER TABLE stock_transfers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE stock_levels ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
