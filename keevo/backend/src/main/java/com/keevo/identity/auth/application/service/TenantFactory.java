package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeRecorder;
import com.keevo.shared.capability.entitlement.PlanSettings;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

/**
 * TenantFactory — Creates and provisions a new isolated tenant workspace.
 *
 * <p>GoF Pattern: Factory — encapsulates complex multi-step tenant creation:
 * code generation → schema creation → foundation module scripts → role seeding → subscription init.
 *
 * <p>Called WITHIN the RegistrationService transaction boundary — rollback on any failure.
 *
 * <p>Architecture note: TenantFactory is in the application layer (not domain)
 * because it depends on infrastructure (TenantSchemaProvisioner) via constructor injection.
 */
@Component
public class TenantFactory {

    private static final Logger log = LoggerFactory.getLogger(TenantFactory.class);

    private final TenantCodeGenerator codeGenerator;
    private final TenantRepository tenantRepository;
    private final TenantSchemaProvisioner schemaProvisioner;
    private final PlanChangeRecorder planChangeRecorder;
    private final ModuleProvisioner moduleProvisioner;
    private final PlanModuleRowSync planModuleRows;
    private final PlanCatalog catalog;

    /** Without module provisioning nor plan-module rows, seeded plan catalogue — tests. */
    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner,
                         PlanChangeRecorder planChangeRecorder) {
        this(codeGenerator, tenantRepository, schemaProvisioner, planChangeRecorder, null, null, PlanCatalog.SEED);
    }

    /** Seeded plan catalogue — tests. */
    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner,
                         PlanChangeRecorder planChangeRecorder,
                         ModuleProvisioner moduleProvisioner,
                         PlanModuleRowSync planModuleRows) {
        this(codeGenerator, tenantRepository, schemaProvisioner, planChangeRecorder, moduleProvisioner,
                planModuleRows, PlanCatalog.SEED);
    }

    @Autowired
    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner,
                         PlanChangeRecorder planChangeRecorder,
                         ModuleProvisioner moduleProvisioner,
                         PlanModuleRowSync planModuleRows,
                         PlanCatalog catalog) {
        this.catalog = catalog;
        this.codeGenerator    = codeGenerator;
        this.tenantRepository = tenantRepository;
        this.schemaProvisioner = schemaProvisioner;
        this.planChangeRecorder = planChangeRecorder;
        this.moduleProvisioner = moduleProvisioner;
        this.planModuleRows = planModuleRows;
    }

    /**
     * Create a fully provisioned tenant workspace.
     *
     * <ol>
     *   <li>Generate unique tenant code (KV-XXXXXX)</li>
     *   <li>Derive schema name (kv_xxxxxx)</li>
     *   <li>Save tenant to public registry</li>
     *   <li>Create PostgreSQL schema + foundation module scripts + seeds</li>
     * </ol>
     *
     * @return the created and persisted Tenant domain object
     * @throws com.keevo.shared.domain.exception.DomainException TENANT_PROVISION_FAILED on any error
     */
    public Tenant create() {
        // 1. Generate unique code
        String code = codeGenerator.generate();
        String schemaName = Tenant.schemaNameFromCode(code);

        // 2. Create and persist tenant domain object
        // SPEC CHANGE 2026-03-06: new tenants start on a trial (Story 1.6 handles expiry). Story 19.7a: which plan and
        // for how long is data (public.plan_settings), read once here and handed to the schema seed.
        PlanSettings trial = catalog.settings();
        Tenant tenant = new Tenant(
            UUID.randomUUID(),
            code,
            schemaName,
            TenantStatus.ACTIVE,
            trial.trialPlan(),
            Instant.now()
        );
        Tenant savedTenant = tenantRepository.save(tenant);

        // 3. Provision tenant schema: CREATE SCHEMA + tables + seed data, in one transaction.
        //    Story 16.12: the tables come from the foundation module migrations (db/modules/<id>/V*.sql), executed
        //    by TenantSchemaProvisioner on its connection (Flyway cannot see the uncommitted schema).
        //    Compensating action (dropSchemaIfExists) prevents orphaned schemas on failure (AC4).
        //    Story 9-2 — plan history starts with the trial (same registration transaction); inside
        //    the try so a failure also drops the provisioned schema.
        try {
            schemaProvisioner.provision(savedTenant.getId().toString(), schemaName, trial.trialPlan(),
                    trial.trialMonths());
            planChangeRecorder.recordEvent(savedTenant.getId(), null, trial.trialPlan(),
                    PlanChangeCause.REGISTRATION, null, null);
            // Story 16.5 — the paid modules get their TRIAL rows (trial end = subscription end) in the
            // registration transaction, before the module provisioning that runs after commit
            if (planModuleRows != null) {
                planModuleRows.sync(savedTenant.getId(), schemaName);
            }
        } catch (RuntimeException e) {
            schemaProvisioner.dropSchemaIfExists(schemaName);
            throw e;
        }

        // Story 13.9 / 16.12 — Flyway for the active modules after the registration commits (the module provisioner
        // reads the committed tenant row): the foundation scripts, already executed above, are re-applied
        // idempotently and their history recorded; paid modules active for the trial get their tables. A failure
        // never fails the registration — the foundation tables already exist, and the next start (or the tenant's
        // first request) provisions what is missing.
        afterCommit(() -> moduleProvisioner.provisionActiveModules(schemaName), savedTenant.getCode());

        return savedTenant;
    }

    private void afterCommit(Runnable action, String tenantCode) {
        if (moduleProvisioner == null) {
            return;
        }
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Module provisioning of new tenant {} failed ({}) — retried at the next start", tenantCode,
                        e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
