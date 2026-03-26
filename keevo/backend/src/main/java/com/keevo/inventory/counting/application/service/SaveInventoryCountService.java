package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * SaveInventoryCountService — saves or updates a physical count for a product in a session.
 */
@Service
public class SaveInventoryCountService implements SaveInventoryCountUseCase {

    private final InventorySessionRepository sessionRepository;
    private final InventoryCountRepository countRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SaveInventoryCountService(InventorySessionRepository sessionRepository,
                                     InventoryCountRepository countRepository,
                                     ApplicationEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.countRepository = countRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public InventoryCount execute(SaveInventoryCountCommand command) {
        InventorySession session = sessionRepository.findById(command.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session d'inventaire introuvable : " + command.sessionId()));

        if (session.getStatus() != InventorySessionStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                    "Cette session d'inventaire n'est pas en cours");
        }

        InventoryCount count = InventoryCount.create(
                command.sessionId(),
                command.productId(),
                command.variantId(),
                command.productName(),
                command.variantLabel(),
                command.theoretical(),
                command.physical(),
                command.actorId());

        InventoryCount saved = countRepository.upsert(count);

        eventPublisher.publishEvent(new InventoryCountSavedEvent(
                saved.getId(), saved.getSessionId(), saved.getProductId(), saved.getVariantId(),
                saved.getTheoretical(), saved.getPhysical(), saved.getEcart(),
                command.actorId(), TenantContext.getCurrentTenant(), Instant.now()));

        return saved;
    }
}
