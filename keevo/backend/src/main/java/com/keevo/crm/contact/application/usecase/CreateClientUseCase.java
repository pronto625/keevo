package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientProfileRules;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.shared.event.catalog.contact.ClientCreatedEvent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CreateClientUseCase — creates a new client and records the audit event (Story 2.5).
 *
 * <p>GoF Pattern: Command — encapsulates all creation inputs in {@link CreateClientCommand}.
 *
 * <p>Story 20.2 — the client always gets a store ({@link ClientStoreScope#storeForNewClient}: a member's own store,
 * the owner's choice or the oldest active store); Bref fields validated by {@link ClientProfileRules}; another
 * non-archived client of the same store with the same E.164 phone does not block the creation, it is returned as a
 * duplicate warning (one indexed probe on {@code (store_id, phone_normalized)}, no unique constraint, no merge).
 */
@Service
public class CreateClientUseCase {

    private final ClientRepository clientRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ClientStoreScope storeScope;

    public CreateClientUseCase(ClientRepository clientRepository,
                               ApplicationEventPublisher eventPublisher,
                               ClientStoreScope storeScope) {
        this.clientRepository = clientRepository;
        this.eventPublisher   = eventPublisher;
        this.storeScope       = storeScope;
    }

    /**
     * @param id               the client id, {@code null} = generated (REST); set by the sync (on-device UUID)
     * @param profile          Bref fields, {@code null} = none
     * @param requestedStoreId the store asked for, {@code null} = default (see {@link ClientStoreScope})
     * @param access           the acting user's access
     * @param audit            false for the sync create (no audit event, as before 20.2)
     */
    public record CreateClientCommand(
            UUID id,
            String name,
            String phone,
            String email,
            String notes,
            ClientProfileInput profile,
            UUID requestedStoreId,
            ClientAccess access,
            UUID actorId,
            String tenantId,
            boolean audit
    ) {
        /** Pre-20.2 arity (REST create, owner access, default store). */
        public CreateClientCommand(String name, String phone, String email, String notes, UUID actorId,
                                   String tenantId) {
            this(null, name, phone, email, notes, ClientProfileInput.NONE, null, ClientAccess.SYSTEM, actorId,
                    tenantId, true);
        }
    }

    /**
     * @param client     the created client
     * @param duplicates other non-archived clients of the same store with the same phone (oldest first), empty
     *                   when none — the {@code DUPLICATE_PHONE} warning
     */
    public record CreateClientResult(Client client, List<Client> duplicates) {
        public CreateClientResult {
            duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
        }
    }

    public CreateClientResult execute(CreateClientCommand cmd) {
        Instant now = Instant.now();
        UUID storeId = storeScope.storeForNewClient(cmd.access(), cmd.requestedStoreId(), cmd.tenantId());
        // Domain validation happens inside the Client record constructor
        Client client = new Client(cmd.id() != null ? cmd.id() : UUID.randomUUID(), cmd.name(), cmd.phone(),
                cmd.email(), cmd.notes(), false, now, now, ClientProfileRules.forCreate(cmd.profile()), storeId,
                MarketingConsent.NONE);
        List<Client> duplicates = clientRepository.findActiveByStoreAndPhone(storeId, client.phoneNormalized())
                .stream().filter(c -> !c.id().equals(client.id())).toList();
        Client saved = clientRepository.save(client);
        if (cmd.audit()) {
            eventPublisher.publishEvent(new ClientCreatedEvent(
                    saved.id(), saved.name(), cmd.actorId(), cmd.tenantId(), now));
        }
        return new CreateClientResult(saved, duplicates);
    }
}
