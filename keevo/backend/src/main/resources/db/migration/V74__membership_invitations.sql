-- V74 — Story 19.6b (fusion Keevo × Bref, epic 19): adding a person who already has an account is an invitation the
-- person accepts or declines, never an immediate attach (consent).
--
--   membership_invitations  one row per (company, person): the role, store and names typed by the owner, the kind of
--                           identifier he typed (PHONE / USERNAME — the only one the company will ever see of that
--                           account) and the state PENDING → ACCEPTED | DECLINED | CANCELLED (expiry by expires_at).
--                           A new invitation of the same pair renews the row in one conditional upsert (not within 30
--                           days of a refusal); last_sent_at keeps one invitation message per pair per 24 h whatever
--                           the re-additions and cancellations. The rolling
--                           daily cap counts rows by (tenant_id, invited_at); the person's pending list reads
--                           (user_id, status). A deleted company or invited account deletes its invitations
--                           (CASCADE). invited_by is informational, without foreign key (the inviter may be a support
--                           account; on acceptance only an active member of the company is notified).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS).
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.membership_invitations (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    user_id         UUID          NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    role            VARCHAR(30)   NOT NULL
                        CONSTRAINT membership_invitations_role_check CHECK (role IN ('MANAGER', 'EMPLOYEE', 'VIEWER')),
    store_id        UUID          NOT NULL,
    first_name      VARCHAR(100)  NOT NULL,
    last_name       VARCHAR(100)  NOT NULL,
    identifier_kind VARCHAR(10)   NOT NULL
                        CONSTRAINT membership_invitations_kind_check CHECK (identifier_kind IN ('PHONE', 'USERNAME')),
    identifier      VARCHAR(100)  NOT NULL,
    invited_by      UUID,
    status          VARCHAR(10)   NOT NULL
                        CONSTRAINT membership_invitations_status_check
                        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    invited_at      TIMESTAMPTZ   NOT NULL,
    expires_at      TIMESTAMPTZ   NOT NULL,
    responded_at    TIMESTAMPTZ,
    last_sent_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_membership_invitations_tenant_user UNIQUE (tenant_id, user_id)
);

-- the person's pending invitations (login step and open session)
CREATE INDEX IF NOT EXISTS idx_membership_invitations_user_status
    ON public.membership_invitations (user_id, status);
-- the company's pending list (partial: answered rows are never scanned)
CREATE INDEX IF NOT EXISTS idx_membership_invitations_tenant_pending
    ON public.membership_invitations (tenant_id, invited_at DESC) WHERE status = 'PENDING';
-- the rolling daily cap per company
CREATE INDEX IF NOT EXISTS idx_membership_invitations_tenant_invited_at
    ON public.membership_invitations (tenant_id, invited_at);
