package com.keevo.mcp.token.domain.port.out;

import com.keevo.mcp.token.domain.model.McpAccessToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.9 — the owners' personal access tokens ({@code public.mcp_access_tokens}). The company is given by its
 * schema name and resolved in the statement itself.
 */
public interface McpAccessTokenRepository {

    /** What an MCP request needs about a token found by hash. */
    record Credential(UUID id, UUID userId, String schema, byte[] tokenHash, List<String> tools, Instant createdAt,
                      Instant expiresAt, Instant lastUsedAt) {
    }

    /** Inserts a token; {@code false} when the schema is not a company. */
    boolean insert(String schema, UUID userId, McpAccessToken token, byte[] tokenHash);

    /** The owner's tokens in the company, newest first (bounded by the per-owner cap). */
    List<McpAccessToken> list(String schema, UUID userId);

    /** Number of tokens the owner holds in the company (expired included until purged). */
    int count(String schema, UUID userId);

    /** The owner's tokens in the company not expired at {@code now} (the cap of 21.9 counts these only). */
    int countActive(String schema, UUID userId, java.time.Instant now);

    /** Deletes the owner's token and returns its name; empty when it is unknown or another user's / company's. */
    Optional<String> delete(String schema, UUID userId, UUID tokenId);

    /** The token with this hash (one indexed lookup), with its company schema. */
    Optional<Credential> findByHash(byte[] tokenHash);

    /** Records a use, only when the last recorded one is older than {@code staleBefore} (one write a minute). */
    void touch(UUID tokenId, Instant usedAt, Instant staleBefore);

    /**
     * Deletes the owner's tokens created before their revocation cutoff ({@code tokens_valid_after}: « tout
     * déconnecter », suspension…): they are refused anyway, so they neither stay listed as working nor use the cap.
     */
    int deleteCutOff(String schema, UUID userId);

    /** Deletes the tokens expired before {@code cutoff}; returns how many. */
    int purgeExpiredBefore(Instant cutoff);
}
