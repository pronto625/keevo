package com.keevo.shared.infrastructure.persistence;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SchemaAwareMultiTenantConnectionProvider — Hibernate SPI that routes every JPA/Hibernate
 * connection to the correct PostgreSQL schema for the current tenant.
 *
 * <p>How it works:
 * <ol>
 *   <li>Hibernate calls {@link #getConnection(String)} before any SQL for the current request.</li>
 *   <li>This provider fetches a connection from HikariCP and executes
 *       {@code SET search_path TO "kv_xxxxxx", public} so that Hibernate SQL targeting
 *       un-qualified table names (e.g. {@code categories}) resolves to the tenant schema.</li>
 *   <li>On {@link #releaseConnection}, the path is reset to {@code public} before the
 *       connection is returned to the pool, preventing tenant leakage.</li>
 * </ol>
 *
 * <p>Integration:
 * <ul>
 *   <li>{@link JwtAuthFilter} sets {@link TenantContext} before the request reaches services.</li>
 *   <li>{@link TenantIdentifierResolver} reads {@link TenantContext} to supply the tenant id.</li>
 *   <li>This provider receives that id and applies the schema routing.</li>
 * </ul>
 *
 * <p>Public schema fallback: when {@code tenantIdentifier} is {@code "public"} or {@code null}
 * (DDL-auto at startup, unauthenticated endpoints), no search_path override is applied.
 *
 * <p>Architecture: shared infrastructure — zero knowledge of business domain.
 */
@Component
public class SchemaAwareMultiTenantConnectionProvider
        implements MultiTenantConnectionProvider<String> {

    private static final String DEFAULT_SCHEMA = TenantIdentifierResolver.DEFAULT_SCHEMA;

    private final DataSource dataSource;

    public SchemaAwareMultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── MultiTenantConnectionProvider ──────────────────────────────────────────

    /** Used by Hibernate for metadata / DDL-auto operations (no tenant context). */
    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close(); // returns to HikariCP pool
    }

    /**
     * Returns a connection with {@code search_path} set to the tenant schema.
     * For the public-schema fallback, no override is applied.
     */
    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection conn = dataSource.getConnection();
        if (isTenantSchema(tenantIdentifier)) {
            applySearchPath(conn, tenantIdentifier);
        }
        return conn;
    }

    /**
     * Resets {@code search_path} to {@code public} before returning the connection to
     * the HikariCP pool to prevent tenant data leaking into the next request.
     */
    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        if (isTenantSchema(tenantIdentifier)) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO public");
            } catch (SQLException e) {
                // Log and continue — worst case, HikariCP will reset the connection
            }
        }
        connection.close(); // returns to HikariCP pool
    }

    /**
     * {@code false}: keep connections open as long as the transaction lasts.
     * Aggressive release (closing the connection mid-transaction) is not compatible
     * with our search_path model.
     */
    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <X> X unwrap(Class<X> unwrapType) {
        if (isUnwrappableAs(unwrapType)) return (X) this;
        throw new IllegalArgumentException("Cannot unwrap as " + unwrapType.getName());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns true when the identifier refers to a real tenant schema (not public). */
    private boolean isTenantSchema(String tenantIdentifier) {
        return tenantIdentifier != null
                && !tenantIdentifier.equals(DEFAULT_SCHEMA)
                && tenantIdentifier.matches("^kv_[a-z0-9]{6}$");
    }

    @SuppressWarnings("SqlSourceToSinkFlow")
    private void applySearchPath(Connection conn, String schemaName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Include public so that shared objects (functions, types) remain accessible.
            stmt.execute("SET search_path TO \"" + schemaName + "\", public");
        }
    }
}
