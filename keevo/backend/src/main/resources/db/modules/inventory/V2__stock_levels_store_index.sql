-- Story 16.13 (reprise 10/11/2026) — store-scoped stock reads. The existing indexes all lead with product_id
-- (uq_stock_level, idx_stock_levels_product_store) and the sync keyset index with updated_at, so a store filter had to
-- scan every level of the tenant. (store_id, updated_at, id::text) serves the store member's keyset pull
-- (WHERE updated_at >= :since AND store_id = :scopeStoreId ORDER BY updated_at, CAST(id AS text)) and, by its leading
-- column, the store-filtered overview aggregate. Same shape as idx_invoices_store_sync (invoicing V16).
CREATE INDEX IF NOT EXISTS idx_stock_levels_store_sync ON stock_levels (store_id, updated_at, (CAST(id AS text)));
