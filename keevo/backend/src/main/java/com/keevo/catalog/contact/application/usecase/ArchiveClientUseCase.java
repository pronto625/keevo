package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.event.ClientArchivedEvent;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * ArchiveClientUseCase — soft-deletes a client and records the audit event (Story 2.5).
 */
@Service
public class ArchiveClientUseCase {

    private final ClientRepository clientRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ArchiveClientUseCase(ClientRepository clientRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.eventPublisher   = eventPublisher;
    }

    public void execute(UUID clientId, UUID actorId, String tenantId) {
        var client = clientRepository.findById(clientId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND,
                        "clientId=" + clientId));
        clientRepository.archive(clientId);
        eventPublisher.publishEvent(new ClientArchivedEvent(
                clientId, client.name(), actorId, tenantId, Instant.now()));
    }
}
