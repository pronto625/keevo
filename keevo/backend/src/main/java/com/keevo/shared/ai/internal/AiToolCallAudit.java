package com.keevo.shared.ai.internal;

import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.8 — where AI tool calls are audited ({@code public.ai_tool_calls}). Never the arguments nor the result.
 */
public interface AiToolCallAudit {

    /**
     * @param tenantSchema the company ({@code kv_xxxxxx}) the call ran for
     * @param actorId      the authenticated user, or {@code null}
     * @param errorCode    domain code of a refusal / error, else {@code null}
     * @param accessTokenId the MCP personal access token the call came through (21.9), else {@code null}
     */
    record AiToolCall(String tenantSchema, UUID actorId, String tool, AiToolChannel channel,
                      AiToolResult.Outcome outcome, String errorCode, long durationMs, Instant calledAt,
                      UUID accessTokenId) {

        public AiToolCall(String tenantSchema, UUID actorId, String tool, AiToolChannel channel,
                          AiToolResult.Outcome outcome, String errorCode, long durationMs, Instant calledAt) {
            this(tenantSchema, actorId, tool, channel, outcome, errorCode, durationMs, calledAt, null);
        }
    }

    void record(AiToolCall call);

    /** Deletes the calls made before {@code cutoff}; returns the number of rows deleted. */
    int purgeBefore(Instant cutoff);
}
