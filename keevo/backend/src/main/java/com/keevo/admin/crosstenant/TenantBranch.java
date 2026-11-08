package com.keevo.admin.crosstenant;

import java.time.Instant;

/**
 * Story 20.13 — a tenant schema taking part in a cross-tenant statement: its id (canonical UUID text), display name
 * (designation, else name, else « Entreprise sans nom »), schema (validated by {@code TenantSchema} before any
 * interpolation), status and creation instant.
 */
public record TenantBranch(String id, String name, String schemaName, String status, Instant createdAt) {
}
