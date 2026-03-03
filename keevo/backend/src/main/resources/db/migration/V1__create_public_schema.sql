-- ============================================================================
-- V1__create_public_schema.sql
-- Public schema tables: tenant registry + global user registry
-- Applied by Spring Boot Flyway auto-configuration at startup
-- ============================================================================

-- ── TENANTS ─────────────────────────────────────────────────────────────────
-- Tenant registry: maps tenant codes to their isolated PostgreSQL schemas
CREATE TABLE IF NOT EXISTS tenants (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(10)   UNIQUE NOT NULL,          -- KV-ABC123
    schema_name  VARCHAR(15)   UNIQUE NOT NULL,          -- kv_abc123
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    plan_type    VARCHAR(20)   NOT NULL DEFAULT 'FREE'
                     CHECK (plan_type IN ('FREE', 'PREMIUM')),
    max_stores   INT           NOT NULL DEFAULT 3,
    max_products INT           NOT NULL DEFAULT 500,
    max_employees INT          NOT NULL DEFAULT 5,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_code        ON tenants(code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_schema_name ON tenants(schema_name);

-- ── GLOBAL USERS ─────────────────────────────────────────────────────────────
-- Global user registry: phone number uniqueness enforced across all tenants
-- Users belong to exactly one tenant (tenant_id FK to tenants)
CREATE TABLE IF NOT EXISTS users (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_number  VARCHAR(20)   UNIQUE NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    role          VARCHAR(20)   NOT NULL CHECK (role IN ('OWNER', 'EMPLOYEE', 'SUPER_ADMIN')),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id),
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_phone     ON users(phone_number);
CREATE        INDEX IF NOT EXISTS idx_users_tenant   ON users(tenant_id);
