package com.keevo.mcp.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code mcp-access} module — « Accès IA externe (MCP) » (Story 21.9, FUS-FR39).
 *
 * <p>Manifest: {@code keevo/modules/mcp-access/module.json} (paid premium module, requires {@code core}, no
 * permission: only the owner manages tokens, each tool keeping its own gates). No tenant table: the tokens are
 * platform data ({@code public.mcp_access_tokens}, V66) found by hash on every MCP request. The offer (which plans
 * include it) is data: V66 grants it to TEAM, BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL. A deactivated module
 * keeps the tokens but refuses every MCP request ({@code MODULE_NOT_ENTITLED}).
 */
@Component
public class McpAccessModule implements KeevoModule {

    public static final String ID = "mcp-access";

    private static final Logger log = LoggerFactory.getLogger(McpAccessModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module mcp-access activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module mcp-access deactivated for tenant {} (tokens kept, MCP refused)", ctx.tenantCode());
    }
}
