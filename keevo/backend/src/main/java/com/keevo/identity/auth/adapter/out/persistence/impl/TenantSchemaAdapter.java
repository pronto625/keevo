package com.keevo.identity.auth.adapter.out.persistence.impl;

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
 * TenantSchemaAdapter — Adapter implementing {@link TenantSchemaPort}.
 *
 * <p>Executes operations that must target a specific tenant schema (kv_xxxxxx)
 * rather than the public schema managed by JPA.
 * Architecture layer: {@code adapter/out/persistence/impl} — infrastructure only.
 */
@Component
public class TenantSchemaAdapter implements TenantSchemaPort {

    private final DataSource dataSource;

    public TenantSchemaAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Assign the OWNER role to the given user in the tenant's isolated schema.
     *
     * @param schemaName tenant schema name (e.g. "kv_abc123")
     * @param userId     UUID of the newly registered user
     */
    @Override
    public void assignOwnerRole(String schemaName, UUID userId) {
        if (!schemaName.matches("^kv_[a-z0-9]{6}$")) {
            throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Invalid schema name: " + schemaName);
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
                        "OWNER role not found in schema " + schemaName);
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
