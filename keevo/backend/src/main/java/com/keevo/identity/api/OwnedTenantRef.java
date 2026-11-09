package com.keevo.identity.api;

/**
 * Story 21.2 — a company the user owns (active OWNER membership), as other modules need it to bind a store purchase.
 *
 * @param tenantCode public company code ({@code KV-XXXXXX})
 * @param schemaName company schema ({@code kv_xxxxxx})
 */
public record OwnedTenantRef(String tenantCode, String schemaName) {
}
