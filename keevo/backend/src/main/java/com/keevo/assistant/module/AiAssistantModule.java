package com.keevo.assistant.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code ai-assistant} module — « Assistant IA » (Story 21.4, Bref « Bref Point » in Keevo, FUS-FR27 / FR39).
 *
 * <p>Manifest: {@code keevo/modules/ai-assistant/module.json} (paid module of every plan, requires {@code core}, no
 * permission: every member role uses it, each tool keeping its own gates). Table:
 * {@code db/modules/ai-assistant/V1__ai_conversations.sql} ({@code ai_conversations}; history
 * {@code flyway_history_ai_assistant}), created only in the schemas of tenants that use the module. The offer (which
 * plans include it) is data: public migration V65 (every plan; free text bounded by the AI_CREDITS quotas).
 */
@Component
public class AiAssistantModule implements KeevoModule {

    public static final String ID = "ai-assistant";

    private static final Logger log = LoggerFactory.getLogger(AiAssistantModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module ai-assistant activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module ai-assistant deactivated for tenant {} (conversations kept)", ctx.tenantCode());
    }
}
