package com.keevo.mcp.server;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Story 21.9 — what the MCP handler needs about the token of the current request (request attribute
 * {@link #ATTRIBUTE}, then MCP transport context): which token, which tools it may call. The owner and the company
 * are the bound session (security context, {@code TenantContext}), never this object.
 *
 * @param tokenId    the token (rate limit, last use)
 * @param tools      tool names of the token's scope
 * @param lastUsedAt last recorded use, null when never used
 */
public record McpRequestSession(UUID tokenId, Set<String> tools, Instant lastUsedAt) {

    public static final String ATTRIBUTE = McpRequestSession.class.getName();

    public McpRequestSession {
        tools = Set.copyOf(tools);
    }
}
