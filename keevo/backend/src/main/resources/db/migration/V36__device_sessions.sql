-- Story 16.11 — long and safe sessions.
--
-- 1. One session per device: every refresh token of a device chain shares a stable session_id
--    (carried over on each rotation). The access token carries it as `sid`; a session is open while
--    one of its refresh tokens is live (not revoked, not expired). Closing a session revokes its
--    chain, so the device's next request answers 401 SESSION_REVOKED.
--    device_id / device_label come from the app (sync device id, "Redmi Note 12 · Android 14");
--    last_used_at is the last login / renewal of the session.
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS device_id varchar(128);
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS device_label varchar(120);
ALTER TABLE public.refresh_tokens ADD COLUMN IF NOT EXISTS last_used_at timestamp(6) with time zone;

-- Existing rows: each live token is its own session (its chain continues under that id).
UPDATE public.refresh_tokens SET session_id = id WHERE session_id IS NULL;
UPDATE public.refresh_tokens SET last_used_at = created_at WHERE last_used_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session ON public.refresh_tokens (session_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_live_by_user
    ON public.refresh_tokens (user_id, tenant_id) WHERE revoked = false;

-- 2. Access-state invalidation broadcast. JwtAuthFilter caches the access state (role, membership,
--    tenant status, revocation cutoff, open sessions) per (user, tenant) for at most 30 s; every
--    instance LISTENs on channel keevo_access_state and evicts on these payloads:
--      u:<user_id>      — everything cached for that user
--      t:<schema_name>  — everything cached for that tenant
--    NOTIFY is delivered on commit, so a rolled-back change never evicts anything. Triggers cover
--    every write to the public tables, including a future service that forgets to evict.
CREATE OR REPLACE FUNCTION public.keevo_notify_access_state_user() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM pg_notify('keevo_access_state', 'u:' || OLD.user_id::text);
        RETURN OLD;
    END IF;
    PERFORM pg_notify('keevo_access_state', 'u:' || NEW.user_id::text);
    IF TG_OP = 'UPDATE' AND OLD.user_id IS DISTINCT FROM NEW.user_id THEN
        PERFORM pg_notify('keevo_access_state', 'u:' || OLD.user_id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION public.keevo_notify_access_state_tenant() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM pg_notify('keevo_access_state', 't:' || OLD.schema_name);
        RETURN OLD;
    END IF;
    PERFORM pg_notify('keevo_access_state', 't:' || NEW.schema_name);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_memberships_access_state ON public.user_tenant_memberships;
CREATE TRIGGER trg_memberships_access_state
    AFTER INSERT OR UPDATE OR DELETE ON public.user_tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION public.keevo_notify_access_state_user();

DROP TRIGGER IF EXISTS trg_tenants_access_state ON public.tenants;
CREATE TRIGGER trg_tenants_access_state
    AFTER UPDATE OF status OR DELETE ON public.tenants
    FOR EACH ROW EXECUTE FUNCTION public.keevo_notify_access_state_tenant();

-- A refresh token revoked outright (sign-out, session closed, replay, password change,
-- deactivation) or superseded closes its session. A plain rotation (replaced_by_id set) keeps
-- the session open and does not evict.
DROP TRIGGER IF EXISTS trg_refresh_tokens_access_state ON public.refresh_tokens;
CREATE TRIGGER trg_refresh_tokens_access_state
    AFTER UPDATE OF revoked ON public.refresh_tokens
    FOR EACH ROW
    WHEN (NEW.revoked AND NOT OLD.revoked AND NEW.replaced_by_id IS NULL)
    EXECUTE FUNCTION public.keevo_notify_access_state_user();

DROP TRIGGER IF EXISTS trg_refresh_tokens_access_state_delete ON public.refresh_tokens;
CREATE TRIGGER trg_refresh_tokens_access_state_delete
    AFTER DELETE ON public.refresh_tokens
    FOR EACH ROW
    WHEN (NOT OLD.revoked)
    EXECUTE FUNCTION public.keevo_notify_access_state_user();
