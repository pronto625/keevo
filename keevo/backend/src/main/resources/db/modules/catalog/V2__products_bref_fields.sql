-- catalog V2 — Story 20.1 (fusion Keevo × Bref): a product carries what a Bref product carries.
--
--   * barcode        — free reference or barcode (≤ 100 characters), NULL = none; NOT unique (several products or
--                      shops may share a reference; deduplication belongs to the importer, 24.3);
--   * product_key    — deduplication key computed by the application with Bref's rules (ProductKeys):
--                      'bc:<barcode>' for a barcode-like code (8–14 digits), else 'nm:<normalized name>'; NOT unique;
--   * price_manual   — true when the price was typed by a person; false only for automatic creations (20.6, 24.3);
--   * merged_into_id — the product this one was merged into (archived, history kept); chains stay flat (one hop).
--
-- Existing rows get product_key / price_manual = true from the one-shot boot job ProductKeyBackfillJob (module
-- scripts never UPDATE data). Every column is nullable or has a constant default: ADD COLUMN is metadata-only.
-- Idempotent forever (run inside registration and by Flyway on every tenant, see db/modules/README.md).
SET LOCAL lock_timeout = '10s';

ALTER TABLE products ADD COLUMN IF NOT EXISTS barcode        VARCHAR(100);
ALTER TABLE products ADD COLUMN IF NOT EXISTS product_key    VARCHAR(250);
ALTER TABLE products ADD COLUMN IF NOT EXISTS price_manual   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE products ADD COLUMN IF NOT EXISTS merged_into_id UUID;
-- a tenant where the column exists without its default (drift) gets it back (metadata only)
ALTER TABLE products ALTER COLUMN price_manual SET DEFAULT false;

-- merged_into_id → products(id). README rule: a foreign key comes only with its table — a drifted tenant whose
-- products table carries no foreign key at all (old LIKE copy) gets none here either. A products table built by the
-- catalog baseline (it already carries its own foreign key to categories) gets this one: added NOT VALID then
-- validated (the new column is NULL everywhere, so the validation is immediate). Guarded: a rerun changes nothing.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                   WHERE c.conname = 'fk_products_merged_into' AND c.conrelid = to_regclass('products'))
       AND EXISTS (SELECT 1 FROM pg_constraint c
                   WHERE c.contype = 'f' AND c.conrelid = to_regclass('products')) THEN
        ALTER TABLE products ADD CONSTRAINT fk_products_merged_into
            FOREIGN KEY (merged_into_id) REFERENCES products(id) NOT VALID;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint c
               WHERE c.conname = 'fk_products_merged_into'
                 AND c.conrelid = to_regclass('products') AND NOT c.convalidated) THEN
        BEGIN
            ALTER TABLE products VALIDATE CONSTRAINT fk_products_merged_into;
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE WARNING 'fk_products_merged_into left NOT VALID in %: orphan rows present', current_schema();
        END;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_products_barcode     ON products(barcode)        WHERE barcode IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_products_product_key ON products(product_key)    WHERE product_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_products_merged_into ON products(merged_into_id) WHERE merged_into_id IS NOT NULL;
