package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.event.catalog.contact.ClientMarketingConsentChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.2 — records ({@code consent = true}: date = now, author = actor) or withdraws ({@code false}: both
 * cleared) a client's consent to marketing messages, Bref semantics: the client's own STOP
 * ({@code marketing_opt_out_at}) is never touched and keeps the client ineligible. A request that changes nothing
 * (consent already given / already absent) keeps the original date and writes no audit row. A client the actor may
 * not see is {@code CLIENT_NOT_FOUND}; a VIEWER never reaches this (write guard).
 */
@Service
public class SetClientMarketingConsentUseCase {

    private final ClientRepository clientRepository;
    private final ClientFinder clientFinder;
    private final ApplicationEventPublisher eventPublisher;

    public SetClientMarketingConsentUseCase(ClientRepository clientRepository, ClientFinder clientFinder,
                                            ApplicationEventPublisher eventPublisher) {
        this.clientRepository = clientRepository;
        this.clientFinder = clientFinder;
        this.eventPublisher = eventPublisher;
    }

    public Client execute(UUID clientId, boolean consent, UUID actorId, String tenantId, ClientAccess access) {
        Client client = clientFinder.require(clientId, access, tenantId);
        if (client.consent().isGiven() == consent) {
            return client;
        }
        Instant now = Instant.now();
        // targeted UPDATE of the consent columns only (never a whole-entity save: no lost update)
        Client saved = client.withConsent(
                consent ? client.consent().given(now, actorId) : client.consent().withdrawn(), now);
        clientRepository.updateConsent(saved.id(), saved.consent(), now);
        eventPublisher.publishEvent(new ClientMarketingConsentChangedEvent(
                saved.id(), saved.name(), consent, saved.storeId(), actorId, tenantId, now));
        return saved;
    }
}
