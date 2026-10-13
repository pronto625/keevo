package com.keevo.admin.subscription.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * TargetTenantLookup — resolves the tenant targeted by a super-admin action from its UUID
 * (Story 9-2). The system tenant (KV-ADMIN), an unknown id and a row whose schema name fails
 * {@link TenantSchema} all answer 404 {@code TENANT_NOT_FOUND}.
 */
@Component
public class TargetTenantLookup {

    private static final Logger log = LoggerFactory.getLogger(TargetTenantLookup.class);

    static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    static final String SELECT_SQL =
            "SELECT id, name, schema_name, status FROM public.tenants "
            + "WHERE id = ? AND id <> '" + ADMIN_TENANT_ID + "'::uuid";

    /** Target tenant of an admin action. */
    public record TargetTenant(UUID id, String name, String schemaName, String status) {
        public boolean isDeleted() {
            return "DELETED".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    public TargetTenantLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TargetTenant load(UUID tenantId) {
        List<TargetTenant> rows = jdbc.query(SELECT_SQL, (rs, i) -> new TargetTenant(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("schema_name"),
                rs.getString("status")), tenantId);
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found: " + tenantId);
        }
        TargetTenant t = rows.get(0);
        if (!TenantSchema.isValid(t.schemaName())) {
            log.warn("Tenant {} has an invalid schema_name — admin action refused", tenantId);
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant schema not found: " + tenantId);
        }
        return t;
    }
}
