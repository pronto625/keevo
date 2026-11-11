package com.keevo.clientportal.module;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code client-portal} module — « Portail client » (Story 21.7, FUS-FR29).
 *
 * <p>Manifest: {@code keevo/modules/client-portal/module.json} (free, business category, requires {@code core}, no
 * permission and no app route: the merchant only switches it in « Mes modules »). No tenant table: the clients, their
 * sessions and the invoice index are platform data ({@code public.portal_clients}, {@code public.portal_sessions},
 * {@code public.invoice_phone_index}, V70). Disabled for a tenant, its invoices disappear from the portal at the next
 * request (the index is kept, they come back when it is enabled again).
 */
@Component
public class ClientPortalModule implements KeevoModule {

    public static final String ID = "client-portal";

    private static final Logger log = LoggerFactory.getLogger(ClientPortalModule.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module client-portal activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module client-portal deactivated for tenant {} (invoices hidden from the portal)", ctx.tenantCode());
    }
}
