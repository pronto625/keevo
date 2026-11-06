-- Story 20.2 (ADR-08) — a restricted device's pull is re-seeded when the stores whose clients it sees change (a store
-- starts / stops sharing its clients, a sharing store is (de)activated): the fingerprint of the client visible stores
-- of the device's last completed pull (SHA-256 hex of the sorted store ids), NULL when unknown or unrestricted.
-- Additive, nullable, metadata-only, idempotent.
ALTER TABLE public.user_sync_state ADD COLUMN IF NOT EXISTS last_scope_client_stores VARCHAR(64);
