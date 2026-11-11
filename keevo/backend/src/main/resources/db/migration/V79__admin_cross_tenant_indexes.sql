-- V79 — Story 19.11c (FUS-NFR11, R1): public indexes of the super-admin cross-tenant reads and of the invitation
-- retention job. Platform data only, no data change. Idempotent (IF NOT EXISTS).
--
--   user_daily_activity (tenant_id, first_seen_at)  the « dernière activité » leg MAX(first_seen_at) WHERE tenant_id = ?
--                                                    becomes one index probe (it read every row of the tenant).
--   user_sync_state (tenant_id)                      sync monitoring detail / alerts: the devices of one company
--                                                    (user_sync_state.tenant_id holds the schema name).
--   membership_invitations, partial on answered or pending rows: the nightly purge of invitations that will never
--                                                    become memberships (DECLINED / CANCELLED answered, PENDING
--                                                    expired) reads only the rows older than the cut-off.
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_tenant_seen
    ON public.user_daily_activity (tenant_id, first_seen_at);

CREATE INDEX IF NOT EXISTS idx_user_sync_state_tenant
    ON public.user_sync_state (tenant_id);

CREATE INDEX IF NOT EXISTS idx_membership_invitations_retention
    ON public.membership_invitations ((COALESCE(responded_at, expires_at)))
    WHERE status <> 'ACCEPTED';
