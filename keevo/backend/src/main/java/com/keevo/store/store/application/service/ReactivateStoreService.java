package com.keevo.store.store.application.service;

import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.event.StoreReactivatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.ReactivateStoreCommand;
import com.keevo.store.store.domain.port.in.ReactivateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.SubscriptionQueryPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ReactivateStoreService — remet en service une boutique désactivée.
 *
 * <p>Une boutique réactivée compte de nouveau dans la limite du forfait : même contrôle
 * (et même verrou par tenant) que la création, pour qu'une réactivation ne permette pas de
 * dépasser le nombre de boutiques autorisé. Réactiver une boutique déjà active est sans effet.
 */
@Service
public class ReactivateStoreService implements ReactivateStoreUseCase {

    private final StoreRepository storeRepository;
    private final SubscriptionQueryPort subscriptionQuery;
    private final PlanLimitPort planLimit;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionLockPort transactionLock;

    public ReactivateStoreService(
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
    public Store execute(ReactivateStoreCommand command) {
        Store existing = storeRepository.findById(command.storeId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                        "Boutique introuvable"));
        if (existing.isActive()) {
            return existing;
        }

        // Même verrou que CreateStoreService / CREATE_STORE : count → save sérialisés par tenant.
        transactionLock.lock("store-limit:" + TenantContext.getCurrentTenant());
        var plan = subscriptionQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "Aucun abonnement actif"));
        planLimit.checkStoreLimit(plan, storeRepository.countActive());

        Store reactivated = new Store(
                existing.id(),
                existing.name(),
                existing.type(),
                existing.address(),
                existing.phone(),
                true,
                existing.createdAt(),
                Instant.now()
        );
        Store saved = storeRepository.save(reactivated);

        eventPublisher.publishEvent(new StoreReactivatedEvent(
                saved.id(), saved.name(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
