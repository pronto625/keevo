package com.keevo.shared.infrastructure.persistence;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * TenantIdentifierResolver — Supplies Hibernate with the current tenant identifier.
 *
 * <p>Reads the schema name stored by {@link JwtAuthFilter} in the thread-local
 * {@link TenantContext}. Hibernate calls this resolver before acquiring any
 * connection so that {@link SchemaAwareMultiTenantConnectionProvider} can
 * route the connection to the correct PostgreSQL schema.
 *
 * <p>Returns {@code "public"} when no tenant is set (e.g. during startup DDL,
 * public-schema operations, or unauthenticated requests). This keeps standard
 * Spring Boot DDL-auto and shared-schema JPA entities working normally.
 *
 * <p>Architecture: shared infrastructure — bridges {@link TenantContext}
 * (set by the JWT middleware) to the Hibernate multi-tenant machinery.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /** Fallback schema used for public-schema entities and unauthenticated operations. */
    public static final String DEFAULT_SCHEMA = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.getCurrentTenant();
        return (tenantId != null && !tenantId.isBlank()) ? tenantId : DEFAULT_SCHEMA;
    }

    /**
     * Return {@code true} so Hibernate validates that the tenant identifier
     * matches the session's current identifier when re-using open sessions.
     * This prevents stale tenant data from leaking across requests.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
