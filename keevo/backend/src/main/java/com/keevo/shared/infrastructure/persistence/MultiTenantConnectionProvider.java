package com.keevo.shared.infrastructure.persistence;

import javax.sql.DataSource;

/**
 * MultiTenantConnectionProvider — Interface for schema-per-tenant connection routing.
 *
 * <p>Template Method pattern: framework supplies the structure,
 * implementation resolves tenant schema at runtime via {@link TenantContext}.
 *
 * <p>Concrete implementation will configure Hibernate multi-tenancy
 * by resolving the current tenant from {@link TenantContext#getCurrentTenant()}.
 */
public interface MultiTenantConnectionProvider {

    /**
     * Get a connection for the given tenant identifier.
     *
     * @param tenantIdentifier the tenant's schema/identifier
     * @return a JDBC DataSource scoped to the tenant
     */
    DataSource getDataSourceForTenant(String tenantIdentifier);

    /**
     * Get the default DataSource (used for shared/public schema operations).
     */
    DataSource getDefaultDataSource();
}
