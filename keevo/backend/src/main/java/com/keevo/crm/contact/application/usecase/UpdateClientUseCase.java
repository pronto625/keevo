package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientProfileRules;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.contact.ClientStoreChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UpdateClientUseCase — updates mutable fields on an existing client (Story 2.5).
 *
 * <p>Story 20.2 — Bref fields patched by presence ({@link ClientProfileRules}); a client the actor may not see is
 * {@code CLIENT_NOT_FOUND}; only the OWNER moves a client to another store (an active store of the tenant) — a member
 * sending another store is {@code FORBIDDEN}. The phone, consent and store columns are never taken from the payload
 * otherwise.
 */
@Service
public class UpdateClientUseCase {

    private final ClientRepository clientRepository;
    private final ClientFinder clientFinder;
    private final ClientStoreScope storeScope;
    private final ApplicationEventPublisher eventPublisher;

    public UpdateClientUseCase(ClientRepository clientRepository, ClientFinder clientFinder,
                               ClientStoreScope storeScope, ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.clientFinder = clientFinder;
        this.storeScope = storeScope;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param profile          Bref fields, {@code null} = unchanged
     * @param requestedStoreId new store (OWNER only), {@code null} = unchanged
     */
    public record UpdateClientCommand(
            UUID clientId,
            String name,
            String phone,
            String email,
            String notes,
            UUID actorId,
            ClientProfileInput profile,
            UUID requestedStoreId,
            ClientAccess access,
            String tenantId
    ) {
        /** Pre-20.2 arity — unrestricted, Bref fields and store unchanged. */
        public UpdateClientCommand(UUID clientId, String name, String phone, String email, String notes,
                                   UUID actorId) {
            this(clientId, name, phone, email, notes, actorId, null, null, ClientAccess.SYSTEM, null);
        }
    }

    public Client execute(UpdateClientCommand cmd) {
        Client existing = clientFinder.require(cmd.clientId(), cmd.access(), cmd.tenantId());
        UUID storeId = existing.storeId();
        if (cmd.requestedStoreId() != null && !cmd.requestedStoreId().equals(existing.storeId())) {
            if (!cmd.access().owner()) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Only the owner can move a client to another store");
            }
            storeId = storeScope.requireActiveStore(cmd.requestedStoreId(), cmd.tenantId());
        }

        // Records are immutable — create updated version with new field values
        Client updated = new Client(
                existing.id(),
                cmd.name()  != null ? cmd.name()  : existing.name(),
                cmd.phone() != null ? cmd.phone() : existing.phone(),
                cmd.email() != null ? cmd.email() : existing.email(),
                cmd.notes() != null ? cmd.notes() : existing.notes(),
                existing.archived(),
                existing.createdAt(),
                Instant.now(),
                ClientProfileRules.patch(existing.profile(), cmd.profile()),
                storeId,
                existing.consent()
        );
        Client saved = clientRepository.save(updated);
        if (!java.util.Objects.equals(existing.storeId(), saved.storeId())) {
            // the owner moved the client: its personal data becomes visible to another team — audited
            eventPublisher.publishEvent(new ClientStoreChangedEvent(saved.id(), saved.name(),
                    storeScope.storeName(cmd.tenantId(), existing.storeId()),
                    storeScope.storeName(cmd.tenantId(), saved.storeId()), saved.storeId(), cmd.actorId(),
                    cmd.tenantId(), saved.updatedAt()));
        }
        return saved;
    }
}
