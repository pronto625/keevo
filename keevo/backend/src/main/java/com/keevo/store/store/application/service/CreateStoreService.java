package com.keevo.store.store.application.service;

import com.keevo.shared.event.catalog.store.StoreKind;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.event.catalog.store.StoreCreatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreFactory;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.port.in.CreateStoreCommand;
import com.keevo.store.store.domain.port.in.CreateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.SubscriptionQueryPort;
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
    private final SubscriptionQueryPort subscriptionQuery;
    private final PlanLimitPort planLimit;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionLockPort transactionLock;

    public CreateStoreService(
            StoreRepository storeRepository,
            SubscriptionQueryPort subscriptionQuery,
            PlanLimitPort planLimit,
            ApplicationEventPublisher eventPublisher,
            TransactionLockPort transactionLock) {
        this.storeRepository = storeRepository;
        this.subscriptionQuery = subscriptionQuery;
        this.planLimit = planLimit;
        this.eventPublisher = eventPublisher;
        this.transactionLock = transactionLock;
    }

    @Override
    @Transactional
    public Store execute(CreateStoreCommand command) {
        // v1s-13-13: serialise count → save per tenant so two concurrent creates at max−1
        // cannot both pass the plan limit (lock held until commit; shared with CREATE_STORE sync).
        transactionLock.lock("store-limit:" + TenantContext.getCurrentTenant());

        // 1. Load current plan and check store limit
        var plan = subscriptionQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"));
        planLimit.checkStoreLimit(plan, storeRepository.countActive());

        // 2. Warehouse uniqueness check
        if (command.type() == StoreType.WAREHOUSE && storeRepository.existsWarehouse()) {
            throw new DomainException(ErrorCode.WAREHOUSE_ALREADY_EXISTS,
                    "Vous avez déjà un entrepôt. Un seul entrepôt est autorisé par compte.");
        }

        // 3. Create via factory (validates name, sets defaults)
        Store store = StoreFactory.create(command.name(), command.type(), command.address(), command.phone());

        // 4. Persist
        Store saved = storeRepository.save(store);

        // 5. Publish audit event
        eventPublisher.publishEvent(new StoreCreatedEvent(
                saved.id(), saved.name(), saved.type() == null ? null : StoreKind.valueOf(saved.type().name()),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
