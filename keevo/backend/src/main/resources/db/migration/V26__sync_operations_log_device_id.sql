-- sync_operations_log.device_id: device that pushed the operation (nullable; NULL for rows logged
-- before this column existed). Last-write-wins uses it to tell a real concurrent overwrite
-- (another device, or a server-side change) from sequential edits made on the same device.
-- Tenant schemas get the same column via TenantSchemaProvisioner.DDL_SYNC_OPERATIONS_LOG_MIGRATE_DEVICE_ID
-- (provisioning + boot-time sync).
ALTER TABLE public.sync_operations_log ADD COLUMN IF NOT EXISTS device_id character varying(100);
