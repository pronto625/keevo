package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.shared.event.catalog.contact.ClientUnarchivedEvent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UnarchiveClientUseCase — restores an archived client and records the audit event; mirror of
 * {@link ArchiveClientUseCase}, same access rules (Story 20.2 — visibility included).
 */
@Service
public class UnarchiveClientUseCase {

    private final ClientRepository clientRepository;
    private final ClientFinder clientFinder;
    private final ApplicationEventPublisher eventPublisher;

    public UnarchiveClientUseCase(ClientRepository clientRepository,
                                  ClientFinder clientFinder,
                                  ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.clientFinder = clientFinder;
        this.eventPublisher = eventPublisher;
    }

    public void execute(UUID clientId, UUID actorId, String tenantId, ClientAccess access) {
        var client = clientFinder.require(clientId, access, tenantId);
        // Replayed operation: already active, nothing to change nor to audit.
        if (!client.archived()) return;
        clientRepository.unarchive(clientId);
        eventPublisher.publishEvent(new ClientUnarchivedEvent(
                clientId, client.name(), actorId, tenantId, Instant.now()));
    }
}
