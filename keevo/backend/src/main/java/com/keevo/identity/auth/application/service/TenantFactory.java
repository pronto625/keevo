package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.infrastructure.persistence.FlywayTenantMigration;
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
 * because it depends on infrastructure (FlywayTenantMigration) via constructor injection.
 */
@Component
public class TenantFactory {

    private final TenantCodeGenerator codeGenerator;
    private final TenantRepository tenantRepository;
    private final FlywayTenantMigration flywayTenantMigration;

    public TenantFactory(TenantCodeGenerator codeGenerator,
                         TenantRepository tenantRepository,
                         FlywayTenantMigration flywayTenantMigration) {
        this.codeGenerator = codeGenerator;
        this.tenantRepository = tenantRepository;
        this.flywayTenantMigration = flywayTenantMigration;
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
        Tenant tenant = new Tenant(
            UUID.randomUUID(),
            code,
            schemaName,
            TenantStatus.ACTIVE,
            PlanType.FREE,
            Instant.now()
        );
        Tenant savedTenant = tenantRepository.save(tenant);

        // 3. Create PostgreSQL schema + run Flyway migrations for this tenant
        //    This creates all tenant tables (products, sales, stock_levels, etc.)
        //    V2 migration seeds default roles (OWNER/EMPLOYEE), subscription (FREE), and placeholder store
        try {
            flywayTenantMigration.migrate(savedTenant.getId().toString(), schemaName);
        } catch (RuntimeException e) {
            // M4: Narrow catch — RuntimeException only; never swallow Error
            // Compensating action: drop orphaned schema to prevent partial tenant state (AC4)
            // DDL (CREATE SCHEMA) runs outside the Spring @Transactional boundary,
            // so we must clean up manually if subsequent migration steps fail.
            flywayTenantMigration.dropSchemaIfExists(schemaName);
            throw e;
        }

        return savedTenant;
    }
}
