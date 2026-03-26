package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.inventory.counting.domain.event.InventorySessionCreatedEvent;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class CreateInventorySessionService implements CreateInventorySessionUseCase {

    private final InventorySessionRepository sessionRepository;
    private final StoreRepository storeRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateInventorySessionService(InventorySessionRepository sessionRepository,
                                         StoreRepository storeRepository,
                                         CategoryRepository categoryRepository,
                                         ApplicationEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.storeRepository = storeRepository;
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public InventorySession execute(CreateInventorySessionCommand command) {
        // 1. Verify store exists
        storeRepository.findById(command.storeId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_STORE_NOT_FOUND,
                        "Store not found: " + command.storeId()));

        // 2. Check no active session for this store
        sessionRepository.findActiveByStoreId(command.storeId()).ifPresent(existing -> {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_ALREADY_ACTIVE,
                    "Active session exists for store",
                    Map.of(
                            "sessionId", existing.getId().toString(),
                            "startedBy", existing.getStartedBy().toString(),
                            "startedAt", existing.getStartedAt().toString()
                    ));
        });

        // 3. Validate categories if PARTIAL scope
        if (command.scope() == InventoryScope.PARTIAL && command.categoryIds() != null) {
            for (UUID catId : command.categoryIds()) {
                categoryRepository.findById(catId)
                        .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_INVALID_CATEGORIES,
                                "Category not found: " + catId));
            }
        }

        // 4. Create session via factory
        InventorySession session = InventorySession.create(
                command.storeId(), command.scope(), command.categoryIds(), command.actorId());

        // 5. Persist
        InventorySession saved = sessionRepository.save(session);

        // 6. Publish event
        eventPublisher.publishEvent(new InventorySessionCreatedEvent(
                saved.getId(), saved.getStoreId(), saved.getScope(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
