package com.keevo.mcp.token.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.9 — a personal access token of an owner, as stored (never the clear token: only its SHA-256 and its last
 * characters).
 *
 * @param id         API handle (revocation)
 * @param name       the owner's label (« Claude du comptable »)
 * @param hint       last 4 characters of the token, to recognise it
 * @param tools      tool names it may call (subset of the owner's tools when it was created)
 * @param createdAt  creation time (compared with the owner's revocation cutoff)
 * @param expiresAt  expiry
 * @param lastUsedAt last MCP request (precision: one minute), null when never used
 */
public record McpAccessToken(UUID id, String name, String hint, List<String> tools, Instant createdAt,
                             Instant expiresAt, Instant lastUsedAt) {

    public McpAccessToken {
        tools = List.copyOf(tools);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
