package com.keevo.mcp.token.adapter.out.persistence;

import com.keevo.shared.domain.port.out.MemberDataPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 16.6 (RGPD, reprise 2026-11-10) — a member removed from a company loses the MCP personal access tokens they
 * hold for it (token name, user id). Tokens are created by owners and an owner cannot be removed, so this only matters
 * for a former owner whose role changed before the removal; one set-based delete on
 * {@code idx_mcp_access_tokens_owner (tenant_id, user_id, …)}.
 */
@Component
public class McpTokenMemberPurger implements MemberDataPurger {

    private final JdbcTemplate jdbc;

    public McpTokenMemberPurger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void purgeMember(String tenantSchema, UUID userId) {
        TenantSchema.validate(tenantSchema);
        jdbc.update("DELETE FROM public.mcp_access_tokens WHERE user_id = ? "
                + "AND tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)", userId, tenantSchema);
    }
}
