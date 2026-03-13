package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.event.StoreDeactivatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
import com.keevo.store.store.domain.port.in.DeactivateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DeactivateStoreService — Soft-deactivates a store (no deletion — data preservation rule).
 *
 * <p>Story 3.1 — AC5.
 */
@Service
public class DeactivateStoreService implements DeactivateStoreUseCase {

    private final StoreRepository storeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeactivateStoreService(StoreRepository storeRepository, ApplicationEventPublisher eventPublisher) {
        this.storeRepository = storeRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Store execute(DeactivateStoreCommand command) {
        Store existing = storeRepository.findById(command.storeId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                        "Boutique introuvable"));

        Store deactivated = new Store(
                existing.id(),
                existing.name(),
                existing.type(),
                existing.address(),
                existing.phone(),
                false,               // is_active = false
                existing.createdAt(),
                Instant.now()
        );

        Store saved = storeRepository.save(deactivated);

        eventPublisher.publishEvent(new StoreDeactivatedEvent(
                saved.id(), saved.name(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
