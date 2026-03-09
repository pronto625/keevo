-- Test schema for products table (H2 database)
CREATE TABLE IF NOT EXISTS products (
    id           UUID        DEFAULT RANDOM_UUID(),
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    sku          VARCHAR(20) NOT NULL,
    category_id  UUID,
    price        INTEGER     NOT NULL DEFAULT 0,
    buy_price    INTEGER     NOT NULL DEFAULT 0,
    transport_cost INTEGER   NOT NULL DEFAULT 0,
    stock_quantity INTEGER   NOT NULL DEFAULT 0,
    photo_url    VARCHAR(500),
    archived     BOOLEAN     NOT NULL DEFAULT FALSE,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    
    CONSTRAINT ck_products_status CHECK (status IN ('ACTIVE', 'DRAFT'))
);

CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku);
CREATE INDEX IF NOT EXISTS idx_products_archived ON products(archived);