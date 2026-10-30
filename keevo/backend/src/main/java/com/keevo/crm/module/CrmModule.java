package com.keevo.crm.module;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.contact.adapter.in.api.ClientLookupAdapter;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.module.CapabilityRegistrar;
import com.keevo.shared.module.ExistingSchemaBaseline;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code crm} module — « Clients et fournisseurs » (Story 13.9, first module converted to {@link KeevoModule}).
 *
 * <p>Manifest: {@code keevo/modules/crm/module.json}. Tables: {@code db/modules/crm/V1__crm_baseline.sql}
 * ({@code clients}, {@code suppliers}, {@code product_suppliers}; history {@code flyway_history_crm}) — the reference
 * DDL. Every tenant schema that already has {@code clients} (all of them: {@code TenantSchemaProvisioner} still
 * creates the crm tables, removal routed to 16.5) is <b>baselined at version 1 without running V1</b>
 * ({@link #existingSchemaBaseline()}): existing tables are never altered. Capability:
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

    /** Existing crm tables are adopted as version 1 (V1 is not executed on them). */
    public static final ExistingSchemaBaseline EXISTING_SCHEMA = new ExistingSchemaBaseline("clients", "1");

    @Override
    public Optional<ExistingSchemaBaseline> existingSchemaBaseline() {
        return Optional.of(EXISTING_SCHEMA);
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
