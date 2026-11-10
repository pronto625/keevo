-- V66 — Story 21.9 (fusion Keevo × Bref, epic 21, FUS-FR39): the external MCP server, paid module « Accès IA externe »
-- (mcp-access).
--
--   mcp_access_tokens         the owners' personal access tokens: an external AI client (Claude, ChatGPT, an
--                             accountant's assistant) calls the Keevo AI tools of 21.8 with one. Only the SHA-256 of
--                             the token is stored (unique index: one lookup per MCP request) with its last 4
--                             characters (hint), the tools it may call, its expiry and its last use. Revocation deletes
--                             the row (refused at the next request, no cache). A deleted company or user deletes its
--                             tokens (CASCADE). Expired tokens are purged 30 days after expiry (McpTokenPurgeJob, range
--                             on expires_at).
--   profile_modules           mcp-access granted to the top tiers of the 21-10 grid: TEAM, BUSINESS, the BUSINESS_TRIAL
--                             registration trial, and the archived complete plans PREMIUM / PREMIUM_TRIAL (which hold
--                             every paid module). FREE and PRO keep the paid-module default (not granted). The offer is
--                             data (ADR-05): a super-admin may change it. Existing profiles only.
--   tenant_module_activation  mcp-access owner-disabled for every existing tenant (db/modules/README.md « Modules
--                             introduced after the go-live »): it stays out of their menus until their owner enables it
--                             in « Mes modules ». Tenants created afterwards follow the initial activation rules.
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING: an edited grant or an owner
-- choice is never overwritten); set-based.
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.mcp_access_tokens (
    id           UUID         PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    user_id      UUID         NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL CONSTRAINT mcp_access_tokens_name_check CHECK (length(btrim(name)) > 0),
    token_hash   BYTEA        NOT NULL CONSTRAINT mcp_access_tokens_hash_check CHECK (length(token_hash) = 32),
    hint         VARCHAR(4)   NOT NULL,
    tools        TEXT[]       NOT NULL CONSTRAINT mcp_access_tokens_tools_check CHECK (cardinality(tools) BETWEEN 1 AND 16),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT mcp_access_tokens_expiry_check CHECK (expires_at > created_at AND expires_at <= created_at + interval '365 days')
);

-- the per-request lookup (one row by hash)
CREATE UNIQUE INDEX IF NOT EXISTS uq_mcp_access_tokens_hash ON public.mcp_access_tokens (token_hash);
-- an owner's tokens in a company, newest first (listing, count before creation)
CREATE INDEX IF NOT EXISTS idx_mcp_access_tokens_owner ON public.mcp_access_tokens (tenant_id, user_id, created_at DESC);
-- the nightly purge of long-expired tokens
CREATE INDEX IF NOT EXISTS idx_mcp_access_tokens_expires_at ON public.mcp_access_tokens (expires_at);
-- the user foreign key (CASCADE on account deletion)
CREATE INDEX IF NOT EXISTS idx_mcp_access_tokens_user ON public.mcp_access_tokens (user_id);

INSERT INTO public.profile_modules (plan_type, module_id, granted)
SELECT p.plan_type, 'mcp-access', true
FROM public.plan_profiles p
WHERE p.plan_type IN ('TEAM', 'BUSINESS', 'BUSINESS_TRIAL', 'PREMIUM', 'PREMIUM_TRIAL')
ON CONFLICT (plan_type, module_id) DO NOTHING;

INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at)
SELECT t.id, 'mcp-access', false, now()
FROM public.tenants t
ON CONFLICT (tenant_id, module_id) DO NOTHING;
