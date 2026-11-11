-- V84 — Story 21.7, reprise 11/11/2026 (fusion Keevo × Bref, FUS-FR29): the client portal behaves like Bref's
-- « retrouver mes factures » (decision of the user, 11/11/2026).
--
--   portal_clients            + name, email, password_hash: Bref's client account — a code sent to the phone once at
--                             registration (name, password, optional e-mail), then sign-in by phone or e-mail and
--                             password (no SMS). The code sign-in of 21.7 stays (it is also how a client who forgot
--                             his password sets a new one). E-mail unique (stored lower-cased: CHECK + partial unique index);
--                             password_hash = BCrypt (cost 12, the platform encoder), never returned.
--   tenant_module_activation  Bref shows every merchant's invoices: client-portal is re-enabled for the tenants V70
--                             owner-disabled. Only V70's own rows go: owner-disabled, no actor (an owner's switch
--                             always records its actor), tenant created before V70 ran (the rule sets of a tenant
--                             created afterwards are kept). An owner who switched the portal off keeps it off.
--   portal_sessions           Bref's client token lives one day: live sessions are cut to 24 h after their creation
--                             (the application opens 24-hour sessions from now on).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS; the DELETE / UPDATE find nothing on a re-run);
-- set-based.
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.portal_clients ADD COLUMN IF NOT EXISTS name          VARCHAR(120);
ALTER TABLE public.portal_clients ADD COLUMN IF NOT EXISTS email         VARCHAR(254);
ALTER TABLE public.portal_clients ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);

-- sign-in by e-mail and its uniqueness: stored lower-cased (checked here, so the plain unique index is
-- case-insensitive whatever writes the row)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'portal_clients_email_lower_check') THEN
        ALTER TABLE public.portal_clients ADD CONSTRAINT portal_clients_email_lower_check
            CHECK (email IS NULL OR email = lower(email));
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_portal_clients_email ON public.portal_clients (email) WHERE email IS NOT NULL;

DELETE FROM public.tenant_module_activation a
USING public.tenants t
WHERE a.tenant_id = t.id
  AND a.module_id = 'client-portal'
  AND NOT a.activated
  AND a.updated_by IS NULL
  AND t.created_at <= (SELECT h.installed_on FROM public.flyway_schema_history h WHERE h.version = '70' AND h.success);

UPDATE public.portal_sessions
SET expires_at = created_at + INTERVAL '1 day'
WHERE expires_at > created_at + INTERVAL '1 day';
