-- V67 — Story 21.4.2 (review of the AI part, 2026-11-10): the MCP personal access token a tool call came through.
--
--   ai_tool_calls.access_token_id   the mcp_access_tokens row (21.9) of a call on channel MCP, else NULL. No foreign
--                                   key on purpose: a revoked token's row is deleted, and the audit must still tell
--                                   which access read what (the owner sees the token's name while it exists; support
--                                   can match the id in the MCP audit events MCP_TOKEN_CREATED / MCP_TOKEN_REVOKED).
--
-- Platform data, public schema only. Idempotent; a nullable column without default (no table rewrite).
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.ai_tool_calls ADD COLUMN IF NOT EXISTS access_token_id UUID;

-- the calls of one access, newest first (incident review after a leak)
CREATE INDEX IF NOT EXISTS idx_ai_tool_calls_access_token ON public.ai_tool_calls (access_token_id, called_at DESC)
    WHERE access_token_id IS NOT NULL;
