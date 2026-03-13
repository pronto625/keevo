package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.event.StoreUpdatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.in.UpdateStoreUseCase;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * UpdateStoreService — Updates store name, address, and phone. Type is immutable.
 *
 * <p>Story 3.1 — AC4.
 */
@Service
public class UpdateStoreService implements UpdateStoreUseCase {

    private final StoreRepository storeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateStoreService(StoreRepository storeRepository, ApplicationEventPublisher eventPublisher) {
        this.storeRepository = storeRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Store execute(UpdateStoreCommand command) {
        Store existing = storeRepository.findById(command.storeId())
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND,
                        "Boutique introuvable"));

        // Validate new name
        String trimmedName = command.name() == null ? null : command.name().trim();
        if (trimmedName == null || trimmedName.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le nom de la boutique est obligatoire");
        }
        if (trimmedName.length() < 2 || trimmedName.length() > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le nom doit contenir entre 2 et 100 caractères");
        }

        // Build updated store — preserve id, type, createdAt
        Store updated = new Store(
                existing.id(),
                trimmedName,
                existing.type(),      // type is IMMUTABLE
                command.address(),
                command.phone(),
                existing.isActive(),
                existing.createdAt(),
                Instant.now()
        );

        Store saved = storeRepository.save(updated);

        eventPublisher.publishEvent(new StoreUpdatedEvent(
                saved.id(), saved.name(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
