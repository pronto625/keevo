package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.springframework.stereotype.Component;

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

    private final TenantCodeGenerator codeGenerator;
    private final TenantRepository tenantRepository;
    private final TenantSchemaProvisioner schemaProvisioner;

    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         TenantSchemaProvisioner schemaProvisioner) {
        this.codeGenerator    = codeGenerator;
        this.tenantRepository = tenantRepository;
        this.schemaProvisioner = schemaProvisioner;
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
        try {
            schemaProvisioner.provision(savedTenant.getId().toString(), schemaName);
        } catch (RuntimeException e) {
            schemaProvisioner.dropSchemaIfExists(schemaName);
            throw e;
        }

        return savedTenant;
    }
}
