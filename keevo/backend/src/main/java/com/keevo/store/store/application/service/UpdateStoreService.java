package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.event.catalog.store.StoreUpdatedEvent;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreProfileRules;
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
 * <p>Story 3.1 — AC4. Story 19.12 — and the Bref fields, partially: a field absent / null keeps its value (a V1
 * body {@code {name, address, phone}} never loses them), see {@link StoreProfileRules}.
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

        // Build updated store — preserve id, type (IMMUTABLE), createdAt, logo; Bref fields patched by presence
        Store updated = StoreProfileRules.apply(
                existing.withContact(trimmedName, command.address(), command.phone(), Instant.now()),
                command.profile());

        Store saved = storeRepository.save(updated);

        StoreProfileRules.Changes changes = StoreProfileRules.changes(existing, saved);
        eventPublisher.publishEvent(new StoreUpdatedEvent(
                saved.id(), saved.name(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now(),
                changes.before(), changes.after()));

        return saved;
    }
}
