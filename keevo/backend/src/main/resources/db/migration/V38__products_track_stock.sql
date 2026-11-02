-- Story 17.12 — per-product stock tracking.
--
-- products.track_stock: false = the product is sold without any stock (service, non-inventoried item): no
-- availability check, no SALE movement, no stock row, excluded from alerts / transfers / inventory / stock
-- exports / stock value. Default true: every existing product stays tracked (zero behaviour change).
-- TenantSchemaSyncService copies this column definition into every tenant schema (existing rows → true);
-- TenantSchemaProvisioner also adds it explicitly (DDL_PRODUCTS_MIGRATE_TRACK_STOCK).
ALTER TABLE public.products ADD COLUMN IF NOT EXISTS track_stock boolean NOT NULL DEFAULT true;
