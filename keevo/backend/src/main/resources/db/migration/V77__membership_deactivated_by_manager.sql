-- Story 19.5b — who made the last deactivation of a membership.
--
-- deactivated_by_manager: TRUE when the membership was deactivated by a delegated team manager (a store member holding
-- the TEAM_MANAGE permission, MANAGER by default), FALSE when the owner, a sync operation, the platform or a member
-- removal did it. Such a manager may only reactivate the memberships he (or another delegate) deactivated; a member the
-- owner deactivated is reactivated by the owner only. Existing rows: FALSE (owner-level, the safe reading).
-- Reactivation clears it. PostgreSQL >= 11 adds a column with a constant default without rewriting the table.
ALTER TABLE public.user_tenant_memberships
    ADD COLUMN IF NOT EXISTS deactivated_by_manager boolean NOT NULL DEFAULT false;
