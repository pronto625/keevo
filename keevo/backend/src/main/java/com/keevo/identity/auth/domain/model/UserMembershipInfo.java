package com.keevo.identity.auth.domain.model;

/**
 * UserMembershipInfo — Projection returned by login step 1.
 *
 * <p>Story 1.7 — Two-step login: this record carries the tenant info needed
 * for the Flutter client to display a tenant picker (or auto-select when only 1 membership).
 *
 * <p>Pure Java record — no JPA, no Spring.
 *
 * @param tenantCode  public tenant code (e.g., "KV-ABC123")
 * @param tenantName  human-readable tenant name (e.g., "Boutique Simon")
 * @param role        user's role in this tenant (e.g., "OWNER", "EMPLOYEE")
 * @param schemaName  internal tenant schema name (e.g., "kv_abc123")
 */
public record UserMembershipInfo(
        String tenantCode,
        String tenantName,
        String role,
        String schemaName
) {}
