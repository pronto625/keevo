-- sales.mobile_money_ref: Mobile Money transaction reference typed at checkout (nullable).
-- The app has always sent it (CREATE_SALE payload, POST /sales) but no column held it, so it
-- was missing on the other devices, after a reinstall and in the exports.
-- Tenant schemas get the same column via TenantSchemaProvisioner.DDL_SALES_MIGRATE_MOBILE_MONEY_REF
-- (provisioning + boot-time sync).
ALTER TABLE public.sales ADD COLUMN IF NOT EXISTS mobile_money_ref character varying(100);
