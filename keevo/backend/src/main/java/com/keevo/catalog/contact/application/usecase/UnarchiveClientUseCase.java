package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.event.ClientUnarchivedEvent;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UnarchiveClientUseCase — restores an archived client and records the audit event; mirror of
 * {@link ArchiveClientUseCase}, same access rules.
 */
@Service
public class UnarchiveClientUseCase {

    private final ClientRepository clientRepository;
    private final ApplicationEventPublisher eventPublisher;

    public UnarchiveClientUseCase(ClientRepository clientRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.eventPublisher = eventPublisher;
    }

    public void execute(UUID clientId, UUID actorId, String tenantId) {
        var client = clientRepository.findById(clientId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND,
                        "clientId=" + clientId));
        // Replayed operation: already active, nothing to change nor to audit.
        if (!client.archived()) return;
        clientRepository.unarchive(clientId);
        eventPublisher.publishEvent(new ClientUnarchivedEvent(
                clientId, client.name(), actorId, tenantId, Instant.now()));
    }
}
