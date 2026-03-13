package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.event.StoreCreatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreFactory;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.in.CreateStoreCommand;
import com.keevo.store.store.domain.port.in.CreateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * CreateStoreService — Creates a store with plan limit and warehouse uniqueness checks.
 *
 * <p>GoF Patterns: Factory (StoreFactory creates the domain object),
 * Observer (publishes StoreCreatedEvent to AuditEventListener).
 *
 * <p>Story 3.1 — AC1, AC2, AC3.
 */
@Service
public class CreateStoreService implements CreateStoreUseCase {

    private final StoreRepository storeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanLimitGuard planLimitGuard;
    private final ApplicationEventPublisher eventPublisher;

    public CreateStoreService(
            StoreRepository storeRepository,
            SubscriptionRepository subscriptionRepository,
            PlanLimitGuard planLimitGuard,
            ApplicationEventPublisher eventPublisher) {
        this.storeRepository = storeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard = planLimitGuard;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Store execute(CreateStoreCommand command) {
        // 1. Load current plan and check store limit
        var planType = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"))
                .getPlanType();
        planLimitGuard.checkStoreLimit(planType, storeRepository.countActive());

        // 2. Warehouse uniqueness check
        if (command.type() == StoreType.WAREHOUSE && storeRepository.existsWarehouse()) {
            throw new DomainException(ErrorCode.WAREHOUSE_ALREADY_EXISTS,
                    "Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte.");
        }

        // 3. Create via factory (validates name, sets defaults)
        Store store = StoreFactory.create(command.name(), command.type(), command.address(), command.phone());

        // 4. Persist
        Store saved = storeRepository.save(store);

        // 5. Publish audit event
        eventPublisher.publishEvent(new StoreCreatedEvent(
                saved.id(), saved.name(), saved.type(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
