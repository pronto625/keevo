-- Story 12.2 (v1s-12-2) — Session revocation <5min (NFR12 / B-HIGH-5 / S2).
-- Nullable: NULL = never revoked → token always valid (backward-compat with existing rows).
-- Updated by raw JdbcTemplate in JdbcTokenRevocationAdapter (NOT mapped in the JPA entity — ddl-auto=validate tolerates orphan DB columns).
ALTER TABLE public.user_tenant_memberships
    ADD COLUMN IF NOT EXISTS tokens_valid_after timestamp(6) with time zone;