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
     */
    record AiToolCall(String tenantSchema, UUID actorId, String tool, AiToolChannel channel,
                      AiToolResult.Outcome outcome, String errorCode, long durationMs, Instant calledAt) {
    }

    void record(AiToolCall call);

    /** Deletes the calls made before {@code cutoff}; returns the number of rows deleted. */
    int purgeBefore(Instant cutoff);
}
