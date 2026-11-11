package com.keevo.mcp.token.adapter.out.persistence;

import com.keevo.shared.domain.port.out.MemberDataPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 16.6 (RGPD, reprise 2026-11-10) — a member removed from a company loses the MCP personal access tokens they
 * hold for it (token name, user id); one set-based delete on {@code idx_mcp_access_tokens_owner (tenant_id, user_id, …)}.
 *
 * <p>21.9 reprise 2026-11-11 — every owner of a company, co-owners included, holds tokens. An owner cannot be removed
 * (20.13), even deactivated: a leaving co-owner is demoted first, which already kills the tokens
 * ({@code tokens_valid_after}); the removal then deletes whatever {@code McpTokenPurgeJob} has not swept yet. A
 * co-owner only deactivated (never removed) loses the rows through that nightly purge.
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
