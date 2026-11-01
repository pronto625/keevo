-- Story 16.13 — per-device memory of the sync pull scope.
--
-- last_scope_restricted / last_scope_store_id: the scope of the device's last COMPLETED pull (restricted = employee;
-- store NULL = unrestricted OWNER pull, or an employee without a store). last_scope_known: false until the device completes a pull with
-- this version (first pull of a device is never a scope change). When a pull starts with a scope different from
-- the known one (employee reassigned), the server answers that pull from the beginning ("reseed": true) so the new
-- store's history reaches the device.
ALTER TABLE public.user_sync_state ADD COLUMN IF NOT EXISTS last_scope_restricted boolean NOT NULL DEFAULT false;
ALTER TABLE public.user_sync_state ADD COLUMN IF NOT EXISTS last_scope_store_id uuid;
ALTER TABLE public.user_sync_state ADD COLUMN IF NOT EXISTS last_scope_known boolean NOT NULL DEFAULT false;
