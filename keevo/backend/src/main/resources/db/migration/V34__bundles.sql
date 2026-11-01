-- V34 — Story 16.2: bundles (MOD-FR12, decision D1: the engine stays 100 % à-la-carte).
--
--   bundle_definitions  a named commercial pack: kebab-case id, French display name, optional description.
--   bundle_modules      its modules (installed, non-core, non-platform module ids), in display order.
--
-- A bundle is never a state of a tenant: applying one (POST /api/v1/admin/tenants/{id}/bundles/{bundleId}) runs one
-- activation plan over its modules through the module lifecycle, which writes per-module tenant_modules rows only.
-- Deleting a bundle removes its definition; tenants that applied it keep their modules. No foreign key to
-- module_definitions: that catalogue is written at boot, after Flyway; the super-admin API validates module ids
-- against the installed manifests. Idempotent (ON CONFLICT DO NOTHING keeps an edited seed). Public schema only.
CREATE TABLE IF NOT EXISTS public.bundle_definitions (
    id          VARCHAR(50)  PRIMARY KEY
                CONSTRAINT bundle_definitions_id_check CHECK (id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name        VARCHAR(100) NOT NULL CONSTRAINT bundle_definitions_name_check CHECK (btrim(name) <> ''),
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  UUID
);

CREATE TABLE IF NOT EXISTS public.bundle_modules (
    bundle_id VARCHAR(50) NOT NULL REFERENCES public.bundle_definitions (id) ON DELETE CASCADE,
    module_id VARCHAR(50) NOT NULL
              CONSTRAINT bundle_modules_module_id_check CHECK (module_id ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    position  SMALLINT    NOT NULL CONSTRAINT bundle_modules_position_check CHECK (position >= 0),
    PRIMARY KEY (bundle_id, module_id)
);

INSERT INTO public.bundle_definitions (id, name, description) VALUES
    ('pro', 'Pack Pro', 'Caisse, stock, facturation et clients')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.bundle_modules (bundle_id, module_id, position) VALUES
    ('pro', 'pos', 0),
    ('pro', 'inventory', 1),
    ('pro', 'invoicing', 2),
    ('pro', 'crm', 3)
ON CONFLICT (bundle_id, module_id) DO NOTHING;
