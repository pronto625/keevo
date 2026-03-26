package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.event.InventorySessionCancelledEvent;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.CancelInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.in.CancelInventorySessionUseCase;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional
public class CancelInventorySessionService implements CancelInventorySessionUseCase {

    private final InventorySessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CancelInventorySessionService(InventorySessionRepository sessionRepository,
                                          ApplicationEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public InventorySession execute(CancelInventorySessionCommand command) {
        var session = sessionRepository.findById(command.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session not found: " + command.sessionId()));

        // RBAC: EMPLOYEE can only cancel their own session
        if ("EMPLOYEE".equals(command.actorRole())
                && !session.getStartedBy().equals(command.actorId())) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Employees can only cancel their own sessions");
        }

        // Domain state machine handles validation
        session.cancel(command.actorId());
        sessionRepository.save(session);

        eventPublisher.publishEvent(new InventorySessionCancelledEvent(
                session.getId(), command.actorId(),
                TenantContext.getCurrentTenant(), Instant.now()));

        return session;
    }
}
