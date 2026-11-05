-- core V3 — Story 19.12 (fusion Keevo × Bref, FUS-FR17): the tenant's default sale flow.
--
--   * tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}, NOT NULL DEFAULT 'POS': every existing tenant keeps
--     the till (POS); the Bref importer (24.x) sets DOCUMENT; the owner can switch it (PUT /api/v1/tenant/sale-flow).
--   * the CHECK is named (tenant_preferences_default_sale_flow_check) and added only when absent, NOT VALID then
--     validated in a guarded block: a legacy row can never abort the upgrade.
--
-- Constant default: ADD COLUMN is metadata-only (no table rewrite). Idempotent forever (run inside registration and
-- by Flyway on every tenant, see db/modules/README.md). No data touched.
SET LOCAL lock_timeout = '10s';

ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS default_sale_flow VARCHAR(10) NOT NULL DEFAULT 'POS';
ALTER TABLE tenant_preferences ALTER COLUMN default_sale_flow SET DEFAULT 'POS';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'tenant_preferences'::regclass
                     AND conname = 'tenant_preferences_default_sale_flow_check') THEN
        ALTER TABLE tenant_preferences ADD CONSTRAINT tenant_preferences_default_sale_flow_check
            CHECK (default_sale_flow IN ('POS', 'DOCUMENT')) NOT VALID;
        BEGIN
            ALTER TABLE tenant_preferences VALIDATE CONSTRAINT tenant_preferences_default_sale_flow_check;
        EXCEPTION WHEN check_violation THEN
            RAISE NOTICE 'tenant_preferences_default_sale_flow_check left NOT VALID (legacy rows)';
        END;
    END IF;
END $$;
