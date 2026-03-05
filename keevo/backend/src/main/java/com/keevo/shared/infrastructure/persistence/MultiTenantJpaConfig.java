package com.keevo.shared.infrastructure.persistence;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * MultiTenantJpaConfig — Registers the Hibernate multi-tenancy infrastructure with Spring Boot.
 *
 * <p>Uses {@link HibernatePropertiesCustomizer} (the recommended Spring Boot hook)
 * to inject two Hibernate SPI implementations:
 * <ul>
 *   <li>{@link SchemaAwareMultiTenantConnectionProvider}: acquires connections with
 *       the correct PostgreSQL {@code search_path} for the current tenant.</li>
 *   <li>{@link TenantIdentifierResolver}: reads the tenant schema name from
 *       {@link TenantContext} (set by {@code JwtAuthFilter}).</li>
 * </ul>
 *
 * <p>With these registered, every JPA/Hibernate operation (SELECT, INSERT, UPDATE, DELETE)
 * automatically targets the tenant schema — no per-repository {@code SET search_path} needed.
 *
 * <p>Architecture: shared infrastructure configuration — no business logic.
 */
@Configuration
public class MultiTenantJpaConfig implements HibernatePropertiesCustomizer {

    private final SchemaAwareMultiTenantConnectionProvider connectionProvider;
    private final TenantIdentifierResolver tenantIdentifierResolver;

    public MultiTenantJpaConfig(SchemaAwareMultiTenantConnectionProvider connectionProvider,
                                 TenantIdentifierResolver tenantIdentifierResolver) {
        this.connectionProvider = connectionProvider;
        this.tenantIdentifierResolver = tenantIdentifierResolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        // Register the connection provider — Hibernate calls getConnection(tenantId)
        // for every transaction instead of acquiring from the standard pool.
        hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);

        // Register the resolver — Hibernate calls resolveCurrentTenantIdentifier()
        // to determine which tenant schema to target for the current thread.
        hibernateProperties.put(
                AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
    }
}
