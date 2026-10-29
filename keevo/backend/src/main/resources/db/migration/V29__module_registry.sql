-- V29__module_registry.sql — Story 13.1 (module registry, spec-socle §2.1 / §2.2)
-- Public schema only: the module catalogue is global, the per-tenant state references tenants logically.

-- 1. Global catalogue, seeded idempotently at boot from the classpath manifests
--    (classpath*:keevo/modules/*/module.json) by ModuleCatalogSeeder. Rows are never deleted by the seed.
CREATE TABLE public.module_definitions
(
  id           VARCHAR(50)  NOT NULL,
  version      VARCHAR(20)  NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  category     VARCHAR(20)  NOT NULL,
  requires     JSONB        NOT NULL DEFAULT '[]'::jsonb,
  enhances     JSONB        NOT NULL DEFAULT '[]'::jsonb,
  is_free      BOOLEAN      NOT NULL DEFAULT FALSE,
  manifest     JSONB        NOT NULL,
  installed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT module_definitions_pkey PRIMARY KEY (id),
  CONSTRAINT module_definitions_category_check CHECK (category IN ('core', 'business', 'premium', 'platform')),
  -- strict MAJOR.MINOR.PATCH: the seed compares versions numerically (no downgrade by an older build)
  CONSTRAINT module_definitions_version_check CHECK (version ~ '^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$')
);

-- 2. Per-tenant module state (the "à la carte" core). Created here, populated from 13.2 / 16.1.
--    tenant_id → public.tenants(id) and module_id → public.module_definitions(id) are logical references.
--    No separate tenant_id index: the UNIQUE (tenant_id, module_id) index serves lookups by tenant_id.
CREATE TABLE public.tenant_modules
(
  id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
  tenant_id           UUID        NOT NULL,
  module_id           VARCHAR(50) NOT NULL,
  status              VARCHAR(20) NOT NULL,
  tier                VARCHAR(20) NOT NULL,
  activated_at        TIMESTAMPTZ,
  expires_at          TIMESTAMPTZ,
  provisioned_version VARCHAR(20),
  grace_until         TIMESTAMPTZ,
  disabled_at         TIMESTAMPTZ,
  CONSTRAINT tenant_modules_pkey PRIMARY KEY (id),
  CONSTRAINT tenant_modules_tenant_module_key UNIQUE (tenant_id, module_id),
  CONSTRAINT tenant_modules_status_check CHECK (status IN ('AVAILABLE', 'TRIAL', 'ACTIVE', 'EXPIRED', 'DISABLED')),
  CONSTRAINT tenant_modules_tier_check CHECK (tier IN ('FREE', 'STANDARD', 'PREMIUM', 'ENTERPRISE'))
);
