package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanCommand;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ActivatePlanService — Activates or upgrades a tenant plan (Super Admin only).
 *
 * <p>Reads the tenant's current subscription row and updates planType + expiresAt.
 * Status is always set back to ACTIVE on activation (un-suspends if suspended).
 *
 * <p>Called by {@code AdminSubscriptionController.activatePlan()}.
 *
 * <p><b>H1 fix (revised) — schemaName as tenant identifier:</b> {@code command.targetTenantId()}
 * is the tenant's {@code schemaName} (e.g., {@code "kv_abc123"}) as stored in
 * {@code LoginResponse.tenantId} and used by the Flutter client. It is NOT the UUID primary key.
 *
 * <p><b>Multi-tenant OEMIV isolation (REQUIRES_NEW):</b>
 * Spring's {@code OpenEntityManagerInViewInterceptor} opens a Hibernate session for the whole request
 * bound to SUPER_ADMIN's tenant (null). Hibernate resolves the tenant at session-creation time, so
 * changing {@code TenantContext} inside a plain {@code @Transactional} method has no effect on the
 * already-bound session. Fix: call {@link TenantContext#setCurrentTenant} FIRST, then delegate JPA
 * work to a {@link TransactionTemplate} with {@code PROPAGATION_REQUIRES_NEW} — this suspends the
 * OEMIV session and opens a fresh Hibernate session that reads the updated TenantContext.
 */
@Service
public class ActivatePlanService implements ActivatePlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ActivatePlanService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final TenantRepository tenantRepository;
    private final TransactionTemplate requiresNewTemplate;

    /** Production constructor — Spring injects the {@link PlatformTransactionManager}. */
    @org.springframework.beans.factory.annotation.Autowired
    public ActivatePlanService(SubscriptionRepository subscriptionRepository,
                               TenantRepository tenantRepository,
                               PlatformTransactionManager transactionManager) {
        this(subscriptionRepository, tenantRepository, buildTemplate(transactionManager));
    }

    /**
     * Testing constructor — accepts a pre-configured {@link TransactionTemplate}.
     * Allows unit tests to mock the template without a real transaction manager.
     */
    ActivatePlanService(SubscriptionRepository subscriptionRepository,
                        TenantRepository tenantRepository,
                        TransactionTemplate requiresNewTemplate) {
        this.subscriptionRepository = subscriptionRepository;
        this.tenantRepository = tenantRepository;
        this.requiresNewTemplate = requiresNewTemplate;
    }

    private static TransactionTemplate buildTemplate(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    @Override
    public void execute(ActivatePlanCommand command) {
        // Step 1: Verify tenant exists via schemaName (public schema — safe with current OEMIV session)
        String schemaName = command.targetTenantId(); // e.g., "kv_abc123" from LoginResponse.tenantId
        Tenant tenant = tenantRepository.findBySchemaName(schemaName)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND,
                        "Tenant not found: " + schemaName));
        log.debug("Tenant resolved: schema={} uuid={}", schemaName, tenant.getId());

        // Step 2: Set TenantContext BEFORE the new transaction opens so Hibernate reads it at
        // session-creation time (inside requiresNewTemplate.execute below).
        String previousTenant = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(schemaName);
        try {
            // Step 3: REQUIRES_NEW suspends the OEMIV session and opens a fresh Hibernate session
            // whose tenant is resolved from TenantContext = schemaName.
            requiresNewTemplate.execute((TransactionCallback<Void>) status -> {
                Subscription current = subscriptionRepository.findActivePlan()
                        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                                "No subscription found for target tenant: " + schemaName));

                Subscription updated = new Subscription(
                        current.getId(),
                        command.newPlan(),
                        SubscriptionStatus.ACTIVE,
                        command.newPlan().getMaxStores(),
                        command.newPlan().getMaxProducts(),
                        command.newPlan().getMaxEmployees(),
                        current.getCreatedAt(),
                        command.expiresAt()
                );

                subscriptionRepository.save(updated);

                log.info("Plan activated: schema={} newPlan={} actor={}",
                        schemaName, command.newPlan(), command.actorId());
                return null;
            });
        } finally {
            // Step 4: Always restore the caller's tenant context (SUPER_ADMIN = null/public)
            TenantContext.setCurrentTenant(previousTenant);
        }
    }
}
