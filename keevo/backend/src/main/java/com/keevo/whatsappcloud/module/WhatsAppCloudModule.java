package com.keevo.whatsappcloud.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code whatsapp-cloud} module — « WhatsApp Business » (Story 21.5, Bref's owner WhatsApp in Keevo, FUS-FR28).
 *
 * <p>Manifest: {@code keevo/modules/whatsapp-cloud/module.json} (paid premium module, requires {@code core}, no
 * permission: only the owner manages the account, the inbox and the templates; every member reads whether the company
 * number is connected). Tables: {@code db/modules/whatsapp-cloud/V1__whatsapp_cloud.sql} (messages, conversations,
 * templates; history {@code flyway_history_whatsapp_cloud}) in the schemas of the tenants that use it, plus the
 * platform table {@code public.whatsapp_accounts} (V68, webhook routing by number). The offer (which plans include it)
 * is data: V68 grants it to BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL. A deactivated module keeps the account
 * and the messages, ignores the webhook deliveries and sends invoices by the platform WhatsApp again.
 */
@Component
public class WhatsAppCloudModule implements KeevoModule {

    public static final String ID = "whatsapp-cloud";

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCloudModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module whatsapp-cloud activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module whatsapp-cloud deactivated for tenant {} (account and messages kept)", ctx.tenantCode());
    }
}
