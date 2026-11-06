-- store V3 — Story 20.2 (ADR-08): a store may share its clients with the other sharing stores of the tenant.
-- A member of a sharing store sees the clients of every active sharing store (plus the legacy clients without store);
-- a member of a non-sharing store sees its own store only. Off by default.
--
-- Constant default: ADD COLUMN is metadata-only. Idempotent forever (see db/modules/README.md). No data touched.
SET LOCAL lock_timeout = '10s';

ALTER TABLE stores ADD COLUMN IF NOT EXISTS share_clients BOOLEAN NOT NULL DEFAULT false;
-- a tenant where the column exists without its default (drift) gets it back (metadata only)
ALTER TABLE stores ALTER COLUMN share_clients SET DEFAULT false;
