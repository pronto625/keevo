-- store V2 — Story 19.12 (fusion Keevo × Bref, FUS-FR12): a store carries what a Bref shop carries.
--
--   * category    — one of the 19 Bref keys (fashion, electronics, …, other), held by the Java enum
--                   store.api.StoreCategory; no CHECK on purpose (the list may grow without a migration);
--   * description — free text, ≤ 1000 characters;
--   * logo_path   — the stored logo file name (<store id>.png|jpg) under keevo.store-logo.storage-dir, NULL = none;
--   * niu, rccm   — the tax number (NIU) and trade register number (RCCM);
--   * country     — ISO 3166-1 alpha-2, NOT NULL DEFAULT 'CM'.
--
-- Every column is nullable or has a constant default: ADD COLUMN is metadata-only (no table rewrite), existing rows
-- read NULL / 'CM'. Idempotent forever (run inside registration and by Flyway on every tenant, see
-- db/modules/README.md). No data touched.
SET LOCAL lock_timeout = '10s';

ALTER TABLE stores ADD COLUMN IF NOT EXISTS category    VARCHAR(30);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS logo_path   VARCHAR(255);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS niu         VARCHAR(30);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS rccm        VARCHAR(50);
ALTER TABLE stores ADD COLUMN IF NOT EXISTS country     VARCHAR(2) NOT NULL DEFAULT 'CM';
-- a tenant where the column exists without its default (drift) gets it back (metadata only)
ALTER TABLE stores ALTER COLUMN country SET DEFAULT 'CM';
