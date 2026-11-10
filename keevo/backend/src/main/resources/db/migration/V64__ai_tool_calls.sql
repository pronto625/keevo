-- V64 — Story 21.8: audit trail of the AI tool calls (in-app assistant 21.4, external MCP server 21.9).
--
--   ai_tool_calls   one row per tool call: which tool, through which channel, by whom, for which company, the outcome
--                   (OK, REFUSED by a role / permission / module gate, INVALID arguments, ERROR), the error code and the
--                   duration. Never the arguments nor the result (no personal content). Written by
--                   shared.ai (JdbcAiToolCallAudit, one INSERT per call), purged after 180 days by AiToolCallPurgeJob
--                   (one set-based DELETE on the called_at index).
--
-- Platform data, public schema only. Idempotent (IF NOT EXISTS).
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS public.ai_tool_calls (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES public.tenants (id) ON DELETE CASCADE,
    actor_id    UUID,
    tool        VARCHAR(64)  NOT NULL,
    channel     VARCHAR(16)  NOT NULL
                CONSTRAINT ai_tool_calls_channel_check CHECK (channel IN ('ASSISTANT', 'MCP', 'INTERNAL')),
    outcome     VARCHAR(16)  NOT NULL
                CONSTRAINT ai_tool_calls_outcome_check CHECK (outcome IN ('OK', 'REFUSED', 'INVALID', 'ERROR')),
    error_code  VARCHAR(60),
    duration_ms INTEGER      NOT NULL CONSTRAINT ai_tool_calls_duration_check CHECK (duration_ms >= 0),
    called_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- a company's recent calls (usage screens, 21.4 / 21.9), newest first
CREATE INDEX IF NOT EXISTS idx_ai_tool_calls_tenant_called_at ON public.ai_tool_calls (tenant_id, called_at DESC);
-- the nightly retention purge (range on called_at)
CREATE INDEX IF NOT EXISTS idx_ai_tool_calls_called_at ON public.ai_tool_calls (called_at);
