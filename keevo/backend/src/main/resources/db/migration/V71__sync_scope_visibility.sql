-- Story 16.13 (reprise 10/11/2026) — per-device memory of the data-visibility rules of the last completed pull.
--
-- last_scope_visibility: marker of a restricted (store member) pull's visibility rules — stock levels limited to the
-- member's store, purchase prices shown or not (permission PURCHASE_PRICE_VIEW). NULL = unknown (the device pulled
-- before these rules) or an unrestricted (owner) pull. A restricted pull that continues from a `since` on a device whose
-- marker differs is answered from the beginning ("reseed": true) once, so the device drops other stores' stock levels
-- and overwrites the purchase prices it pulled before. Nullable, no default: no rewrite of existing rows.
ALTER TABLE public.user_sync_state ADD COLUMN IF NOT EXISTS last_scope_visibility text;
