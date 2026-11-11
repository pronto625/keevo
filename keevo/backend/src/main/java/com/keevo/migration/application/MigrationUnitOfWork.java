package com.keevo.migration.application;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Story 24.2 — one transaction per import step: a new transaction opened either on the platform (no tenant context:
 * the tenant creation, like a registration) or in the imported tenant's schema (the tenant context is bound before the
 * transaction starts, so the JPA session and the JDBC connection use that schema — same pattern as the super-admin
 * {@code TenantScope}). The previous tenant context is restored afterwards.
 */
@Component
public class MigrationUnitOfWork {

    private final TransactionTemplate tx;

    public MigrationUnitOfWork(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs {@code work} in a new transaction without tenant context. */
    public <T> T platform(Supplier<T> work) {
        return scoped(null, work);
    }

    /** Runs {@code work} in a new transaction bound to the tenant schema. */
    public <T> T tenant(String tenantSchema, Supplier<T> work) {
        return scoped(TenantSchema.validate(tenantSchema), work);
    }

    private <T> T scoped(String schema, Supplier<T> work) {
        String previous = TenantContext.getCurrentTenant();
        if (schema == null) {
            TenantContext.clear();
        } else {
            TenantContext.setCurrentTenant(schema);
        }
        try {
            return tx.execute(status -> work.get());
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }
}
