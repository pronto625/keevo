-- Story 19.14 — a store member without « Voir les ventes de toute la boutique » (STORE_SALES_VIEW) pulls his own sales
-- only: WHERE updated_at >= :since AND store_id = :scopeStoreId AND employee_id = :scopeActorId
-- ORDER BY updated_at, CAST(id AS text). idx_sales_store_employee_occurred only covers the equality prefix (its third
-- column is occurred_at), so every page re-sorted all of the seller's sales; this index serves the keyset directly.
-- Same shape as idx_stock_levels_store_sync (inventory V2) and idx_invoices_store_sync (invoicing V16).
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_sales_store_employee_sync
    ON sales (store_id, employee_id, updated_at, (CAST(id AS text)));
