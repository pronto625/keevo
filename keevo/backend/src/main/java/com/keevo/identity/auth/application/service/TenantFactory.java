package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanChangeRecorder;
import com.keevo.shared.module.ModuleProvisioner;
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
 * code generation → schema creation → Flyway migration → role seeding → subscription init.
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

    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner,
                         PlanChangeRecorder planChangeRecorder) {
        this(codeGenerator, tenantRepository, schemaProvisioner, planChangeRecorder, null);
    }

    @Autowired
    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner,
                         PlanChangeRecorder planChangeRecorder,
                         ModuleProvisioner moduleProvisioner) {
        this.codeGenerator    = codeGenerator;
        this.tenantRepository = tenantRepository;
        this.schemaProvisioner = schemaProvisioner;
        this.planChangeRecorder = planChangeRecorder;
        this.moduleProvisioner = moduleProvisioner;
    }

    /**
     * Create a fully provisioned tenant workspace.
     *
     * <ol>
     *   <li>Generate unique tenant code (KV-XXXXXX)</li>
     *   <li>Derive schema name (kv_xxxxxx)</li>
     *   <li>Save tenant to public registry</li>
     *   <li>Create PostgreSQL schema + run Flyway migrations</li>
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
        // SPEC CHANGE 2026-03-06: new tenants start on 6-month PREMIUM_TRIAL (Story 1.6 handles expiry)
        Tenant tenant = new Tenant(
            UUID.randomUUID(),
            code,
            schemaName,
            TenantStatus.ACTIVE,
            PlanType.PREMIUM_TRIAL,
            Instant.now()
        );
        Tenant savedTenant = tenantRepository.save(tenant);

        // 3. Provision tenant schema: CREATE SCHEMA + DDL tables + seed data
        //    Programmatic DDL defined in TenantSchemaProvisioner (no SQL files).
        //    Compensating action (dropSchemaIfExists) prevents orphaned schemas on failure (AC4).
        //    Story 9-2 — plan history starts with the trial (same registration transaction); inside
        //    the try so a failure also drops the provisioned schema.
        try {
            schemaProvisioner.provision(savedTenant.getId().toString(), schemaName);
            planChangeRecorder.recordEvent(savedTenant.getId(), null, PlanType.PREMIUM_TRIAL,
                    PlanChangeCause.REGISTRATION, null, null);
        } catch (RuntimeException e) {
            schemaProvisioner.dropSchemaIfExists(schemaName);
            throw e;
        }

        // Story 13.9 — the module migrations of the active (free) modules, once the core DDL exists: after the
        // registration commits (the module provisioner reads the committed tenant row); a failure never fails the
        // registration — the tables already exist (core DDL) and the next start provisions it again.
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
