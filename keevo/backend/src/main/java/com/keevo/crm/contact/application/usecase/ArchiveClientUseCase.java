package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.shared.event.catalog.contact.ClientArchivedEvent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * ArchiveClientUseCase — soft-deletes a client and records the audit event (Story 2.5). Story 20.2 — a client the
 * actor may not see is {@code CLIENT_NOT_FOUND}.
 */
@Service
public class ArchiveClientUseCase {

    private final ClientRepository clientRepository;
    private final ClientFinder clientFinder;
    private final ApplicationEventPublisher eventPublisher;

    public ArchiveClientUseCase(ClientRepository clientRepository,
                                ClientFinder clientFinder,
                                ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.clientFinder     = clientFinder;
        this.eventPublisher   = eventPublisher;
    }

    public void execute(UUID clientId, UUID actorId, String tenantId, ClientAccess access) {
        var client = clientFinder.require(clientId, access, tenantId);
        clientRepository.archive(clientId);
        eventPublisher.publishEvent(new ClientArchivedEvent(
                clientId, client.name(), actorId, tenantId, Instant.now()));
    }
}
