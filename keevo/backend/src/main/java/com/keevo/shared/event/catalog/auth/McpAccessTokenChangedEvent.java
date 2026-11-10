package com.keevo.shared.event.catalog.auth;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.9 — an owner created or revoked a personal access token of the MCP server (« Accès IA externe »). Audited
 * as « Accès IA externe créé » / « Accès IA externe révoqué » with the token's name only (never the token).
 *
 * @param actorId    the owner
 * @param tenantId   account schema
 * @param tokenId    the token
 * @param tokenName  the owner's label of the token
 * @param change     {@link Change}
 * @param occurredAt when
 */
public record McpAccessTokenChangedEvent(
        UUID actorId,
        String tenantId,
        UUID tokenId,
        String tokenName,
        Change change,
        Instant occurredAt
) implements DomainEvent {

    public enum Change { CREATED, REVOKED }
}
