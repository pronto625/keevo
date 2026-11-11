-- Story 19.14 — roles defined by the owner of a company.
--
-- tenant_roles: one row per custom role of a company (« Caissier », « Magasinier »…).
--   base_role   the built-in role whose baseline behaviour the role keeps (MANAGER, EMPLOYEE or VIEWER): selling,
--               read-only for a viewer, team targets, compatibility of installed V1 apps. Immutable once created.
--   permissions the module permissions the owner chose (declared by the manifests of the company's modules); they
--               replace the built-in role -> permission mapping for the members holding the role.
--   store_ids   NULL = every store; otherwise the role only applies to members assigned to one of these stores
--               (a member outside the list holds no permission — fail closed).
-- user_tenant_memberships.custom_role_id: the custom role of a store member (NULL = the built-in role's defaults).
-- A role still assigned is not deleted by the API (409 ROLE_IN_USE); the FK clears the column (SET NULL) so that a
-- company deletion cascading to its roles is never blocked by its own memberships.
CREATE TABLE IF NOT EXISTS public.tenant_roles (
    id          uuid                     NOT NULL PRIMARY KEY,
    tenant_id   uuid                     NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    name        varchar(60)              NOT NULL,
    base_role   varchar(30)              NOT NULL,
    permissions text[]                   NOT NULL DEFAULT '{}',
    store_ids   uuid[],
    created_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp(6) with time zone NOT NULL DEFAULT now(),
    created_by  uuid,
    CONSTRAINT ck_tenant_roles_base_role CHECK (base_role IN ('MANAGER', 'EMPLOYEE', 'VIEWER')),
    CONSTRAINT ck_tenant_roles_name CHECK (length(btrim(name)) BETWEEN 1 AND 60)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_tenant_roles_tenant_name ON public.tenant_roles (tenant_id, lower(name));

ALTER TABLE public.user_tenant_memberships
    ADD COLUMN IF NOT EXISTS custom_role_id uuid REFERENCES public.tenant_roles (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_utm_custom_role
    ON public.user_tenant_memberships (custom_role_id) WHERE custom_role_id IS NOT NULL;

-- Access-state cache (Story 16.11): a change of a role evicts every cached access state of its company on every
-- instance (memberships already broadcast u:<user_id> through trg_memberships_access_state).
CREATE OR REPLACE FUNCTION public.keevo_notify_access_state_tenant_role() RETURNS trigger AS $$
DECLARE
    v_schema text;
BEGIN
    SELECT t.schema_name INTO v_schema FROM public.tenants t
     WHERE t.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    IF v_schema IS NOT NULL THEN
        PERFORM pg_notify('keevo_access_state', 't:' || v_schema);
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tenant_roles_access_state ON public.tenant_roles;
CREATE TRIGGER trg_tenant_roles_access_state
    AFTER INSERT OR UPDATE OR DELETE ON public.tenant_roles
    FOR EACH ROW EXECUTE FUNCTION public.keevo_notify_access_state_tenant_role();
