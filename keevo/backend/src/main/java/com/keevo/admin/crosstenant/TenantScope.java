package com.keevo.admin.crosstenant;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Story 20.13 — runs a super-admin write in ONE account: the tenant context is switched to the account's schema and a
 * new transaction is opened (so the JPA session and the JDBC connection use that schema), then the previous context
 * is restored. The work calls the owning module's api ports, which apply their own rules, events and audit.
 */
@Component
public class TenantScope {

    private final TransactionTemplate tx;

    public TenantScope(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T call(TenantBranch tenant, Supplier<T> work) {
        String schema = TenantSchema.validate(tenant.schemaName());
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schema);
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

    public void run(TenantBranch tenant, Runnable work) {
        call(tenant, () -> {
            work.run();
            return null;
        });
    }
}
