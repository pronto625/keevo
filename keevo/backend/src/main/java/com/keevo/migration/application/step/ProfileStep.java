package com.keevo.migration.application.step;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowCommandPort;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.shared.module.ModuleActivationRulesPort;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Story 24.2, §10.2 step 1 (profile) — once per tenant, in one transaction: the preferences (sector of the oldest shop,
 * scheduled reports and alerts off) and the suspension trace, the sale flow POS → DOCUMENT through
 * {@link TenantSaleFlowCommandPort} (audited), the DOCUMENT activation rules (19.13), then every owner-toggleable module
 * without a Bref equivalent owner-disabled (decision D4). A run that finds the preferences already there changes
 * nothing: an owner's later choices (flow, modules) are never reset by a re-run.
 */
@Component
@Order(20)
class ProfileStep implements ImportStep {

    /** Suspension reason of an imported tenant until its wave activates it (shown in the admin drawer). */
    static final String SUSPENSION_REASON = "Migration Bref en cours — activation en attente";

    private final LegacyIdentityImportPort identity;
    private final TenantSaleFlowCommandPort saleFlow;
    private final ModuleActivationRulesPort rules;
    private final MigrationUnitOfWork uow;

    ProfileStep(LegacyIdentityImportPort identity, TenantSaleFlowCommandPort saleFlow,
                ModuleActivationRulesPort rules, MigrationUnitOfWork uow) {
        this.identity = identity;
        this.saleFlow = saleFlow;
        this.rules = rules;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "profil de l'entreprise";
    }

    @Override
    public java.util.Set<String> targetTables() {
        return java.util.Set.of();
    }

    @Override
    public void run(ImportRun run) {
        String schema = run.tenantSchema();
        String sector = BrefMappings.ownerSector(run.snapshot().shops());
        uow.platform(() -> {
            if (!identity.prepareImportedProfile(schema, run.tenantId(), sector, SUSPENSION_REASON)) {
                return null; // profile already applied by a previous run
            }
            saleFlow.set(schema, SaleFlow.DOCUMENT);
            rules.applyInitialRules(schema);
            rules.ownerDisableAllBut(schema, BrefMappings.BREF_EQUIVALENT_MODULES);
            return null;
        });
    }
}
