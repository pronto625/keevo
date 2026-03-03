-- ============================================================================
-- V2__seed_default_data.sql
-- Seeds default roles, subscription, and placeholder store for new tenants
-- Applied programmatically by FlywayTenantMigration.migrate() per tenant
-- ============================================================================

-- ── Default roles with permissions (AC1: OWNER + EMPLOYEE initialized) ───────
INSERT INTO roles (name, permissions) VALUES
    ('OWNER', '{"all": true}'::jsonb),
    ('EMPLOYEE', '{"pos": true, "inventory_view": true, "stock_view": true}'::jsonb);

-- ── Free subscription with limits (AC1: 3 stores, 500 products, 5 employees) ─
INSERT INTO subscriptions (plan_type, max_stores, max_products, max_employees, status)
VALUES ('FREE', 3, 500, 5, 'ACTIVE');

-- ── Placeholder store (renamed during onboarding — Story 1.4) ───────────────
INSERT INTO stores (name) VALUES ('Ma Boutique');
