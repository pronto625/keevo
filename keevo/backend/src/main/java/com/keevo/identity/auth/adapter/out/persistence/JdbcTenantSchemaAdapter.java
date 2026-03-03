package com.keevo.identity.auth.adapter.out.persistence;

import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * JdbcTenantSchemaAdapter — JDBC adapter implementing TenantSchemaPort.
 *
 * <p>Executes raw SQL against the isolated tenant schema ({@code kv_xxxxxx}).
 * Standard JPA repositories operate in the public schema via the global connection pool;
 * this adapter explicitly names the target schema in every statement.
 *
 * <p>Architecture: this is an adapter/out/persistence — infrastructure layer only.
 * No domain logic belongs here.
 */
@Component
public class JdbcTenantSchemaAdapter implements TenantSchemaPort {

    private final DataSource dataSource;

    public JdbcTenantSchemaAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Assign the OWNER role to the given user in the tenant's isolated schema.
     *
     * <p>Looks up the OWNER role UUID from {@code {schemaName}.roles},
     * then inserts a row into {@code {schemaName}.user_roles}.
     *
     * <p>Called by {@link com.keevo.identity.auth.application.service.RegistrationService}
     * AFTER the user is persisted to public.users (so the UUID is known).
     *
     * @param schemaName tenant schema name (e.g. "kv_abc123") — validated by regex guard
     * @param userId     UUID of the newly registered user
     * @throws DomainException TENANT_PROVISION_FAILED if role cannot be assigned
     */
    @Override
    public void assignOwnerRole(String schemaName, UUID userId) {
        // Safety guard: never target an unexpected schema name
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Invalid schema name for role assignment: " + schemaName);
        }

        try (Connection conn = dataSource.getConnection()) {
            UUID ownerRoleId = fetchOwnerRoleId(conn, schemaName);
            insertUserRole(conn, schemaName, userId, ownerRoleId);
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Failed to assign OWNER role in schema " + schemaName + ": " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private UUID fetchOwnerRoleId(Connection conn, String schemaName) throws SQLException {
        String sql = "SELECT id FROM \"" + schemaName + "\".roles WHERE name = 'OWNER'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                        "OWNER role not found in schema " + schemaName + " — did V2 migration run?");
            }
            return rs.getObject("id", UUID.class);
        }
    }

    private void insertUserRole(Connection conn, String schemaName,
                                 UUID userId, UUID roleId) throws SQLException {
        String sql = "INSERT INTO \"" + schemaName + "\".user_roles (user_id, role_id) " +
                     "VALUES (?, ?) ON CONFLICT (user_id, role_id) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setObject(2, roleId);
            ps.executeUpdate();
        }
    }
}
