package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.event.ClientCreatedEvent;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * CreateClientUseCase — creates a new client and records the audit event (Story 2.5).
 *
 * <p>GoF Pattern: Command — encapsulates all creation inputs in {@link CreateClientCommand}.
 */
@Service
public class CreateClientUseCase {

    private final ClientRepository clientRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateClientUseCase(ClientRepository clientRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.eventPublisher   = eventPublisher;
    }

    public record CreateClientCommand(
            String name,
            String phone,
            String email,
            String notes,
            UUID actorId,
            String tenantId
    ) {}

    public Client execute(CreateClientCommand cmd) {
        Instant now = Instant.now();
        // Domain validation happens inside the Client record constructor
        Client client = new Client(UUID.randomUUID(), cmd.name(), cmd.phone(),
                cmd.email(), cmd.notes(), false, now, now);
        Client saved = clientRepository.save(client);
        eventPublisher.publishEvent(new ClientCreatedEvent(
                saved.id(), saved.name(), cmd.actorId(), cmd.tenantId(), now));
        return saved;
    }
}
