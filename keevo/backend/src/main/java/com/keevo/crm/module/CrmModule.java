package com.keevo.crm.module;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.contact.adapter.in.api.ClientLookupAdapter;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.module.CapabilityRegistrar;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


/**
 * The {@code crm} module — « Clients et fournisseurs » (Story 13.9, first module converted to {@link KeevoModule}).
 *
 * <p>Manifest: {@code keevo/modules/crm/module.json}. Tables: {@code db/modules/crm/V1__crm_baseline.sql}
 * ({@code clients}, {@code suppliers}, {@code product_suppliers}; history {@code flyway_history_crm}) — their single
 * author (Story 16.12: a foundation module, executed at registration and kept up to date on every tenant). V1 is
 * idempotent: on an existing tenant never adopted it only adds missing indexes and defaults (never a foreign key);
 * tenants baselined at 1 by Story 13.9 keep that history and nothing is re-run. Capability:
 * {@link ClientLookupPort}, provided here (not by annotation). Activation and deactivation per tenant have no extra
 * work: the tables come from the migrations, the data is kept when disabled, and the state-driven gates (capability
 * registry, sync guard, entitlement aspect) follow the tenant's module state.
 */
@Component
public class CrmModule implements KeevoModule {

    public static final String ID = "crm";

    private static final Logger log = LoggerFactory.getLogger(CrmModule.class);

    private final ClientRepository clients;

    public CrmModule(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void registerCapabilities(CapabilityRegistrar registrar) {
        registrar.provide(ClientLookupPort.class, new ClientLookupAdapter(clients));
    }

    @Override
    public void onActivate(ModuleTenantContext ctx) {
        log.debug("Module crm activated for tenant {}", ctx.tenantCode());
    }

    @Override
    public void onDeactivate(ModuleTenantContext ctx) {
        log.debug("Module crm deactivated for tenant {} (data kept)", ctx.tenantCode());
    }
}
