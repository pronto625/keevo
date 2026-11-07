package com.keevo.reporting.revenue.adapter.out.persistence;

import com.keevo.reporting.revenue.domain.port.out.RevenueQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Story 20.9 (R4) — the revenue source of the current tenant: {@link RevenueRelations} with the branches its modules
 * allow (module autonomy).
 * <ul>
 *   <li>sales while {@code pos} is not DISABLED;</li>
 *   <li>invoices and credit notes while {@code invoicing} is not DISABLED — after
 *       {@link ModuleProvisioner#ensureProvisioned} (warm: in-memory, no DB work), so the module's tables are at the
 *       version the SQL reads; a provisioning failure is the usual 503 of any invoicing call, never a partial
 *       figure.</li>
 * </ul>
 * No tenant in context (or an invalid / platform schema), no registry: sales only (V1 behaviour). An unreadable module
 * state: sales only, logged at WARN. Cost: one module-state read per call, request-cached by the registry.
 */
@Component
public class ModuleAwareRevenueSource implements RevenueQueryPort {

    private static final Logger log = LoggerFactory.getLogger(ModuleAwareRevenueSource.class);

    static final String POS = "pos";
    static final String INVOICING = "invoicing";

    private final ObjectProvider<ModuleRegistry> registry;
    private final ObjectProvider<ModuleProvisioner> provisioner;

    public ModuleAwareRevenueSource(ObjectProvider<ModuleRegistry> registry,
                                    ObjectProvider<ModuleProvisioner> provisioner) {
        this.registry = registry;
        this.provisioner = provisioner;
    }

    @Override
    public String documents() {
        Branches b = branches();
        return RevenueRelations.documents(b.sales(), b.invoicing());
    }

    @Override
    public String lines() {
        Branches b = branches();
        return RevenueRelations.lines(b.sales(), b.invoicing());
    }

    /** Which sources the current tenant's revenue reads. */
    record Branches(boolean sales, boolean invoicing) {
        static final Branches POS_ONLY = new Branches(true, false);
    }

    Branches branches() {
        String schema = TenantContext.getCurrentTenant();
        ModuleRegistry modules = registry.getIfAvailable();
        if (modules == null || !TenantSchema.isValid(schema)) {
            return Branches.POS_ONLY;
        }
        Map<String, ModuleState> states;
        try {
            states = modules.moduleStates(schema);
        } catch (RuntimeException e) {
            log.warn("revenue source: module states unreadable ({}) — sales only", e.getClass().getSimpleName());
            return Branches.POS_ONLY;
        }
        boolean sales = states.getOrDefault(POS, ModuleState.DISABLED).readable();
        boolean invoicing = states.getOrDefault(INVOICING, ModuleState.DISABLED).readable();
        if (invoicing) {
            ModuleProvisioner p = provisioner.getIfAvailable();
            if (p != null) {
                p.ensureProvisioned(schema, INVOICING);
            }
        }
        return new Branches(sales, invoicing);
    }
}
