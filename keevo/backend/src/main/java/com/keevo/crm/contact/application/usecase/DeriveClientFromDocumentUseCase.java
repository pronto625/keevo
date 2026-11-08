package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.api.DocumentClientInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.event.catalog.contact.ClientCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.6 — finds or creates the CRM client of an issued document (Bref {@code customer.repository} upsert on
 * (shop, phone), FUS-FR16), called by invoicing through {@code ClientLookupPort#findOrCreateForDocument}.
 *
 * <p>Rules: the phone must normalise to E.164 ({@link PhoneNumbers#toE164}, Cameroon default); the store is the
 * actor's establishment (OWNER: the oldest active store, possibly none; store member: its assigned store, none →
 * no derivation). Match = a non-archived client of that store with the same {@code phone_normalized}, else a legacy
 * client without store; several → the oldest. Found → only its empty email / RCCM / NIU are filled (COALESCE, Bref
 * semantics; name and other fields never overwritten). Not found → created like a client typed by the actor
 * ({@link Client} validation, {@link ClientCreatedEvent} for the audit; consent untouched).
 *
 * <p>Concurrency: a transaction advisory lock on (schema, store, phone) serialises two issues for the same phone, so
 * the second one sees the client the first created. Cost: 1 lock + 1 indexed probe ({@code idx_clients_store_phone})
 * + 1 INSERT or 1 conditional UPDATE (+ 1 stores query for an OWNER).
 *
 * <p>Never throws for a business reason (the caller's transaction must not become rollback-only): every refusal is
 * an empty answer, logged at debug.
 */
@Service
public class DeriveClientFromDocumentUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeriveClientFromDocumentUseCase.class);

    /** Column lengths of {@code clients} (a longer value is not copied rather than failing the issue). */
    static final int MAX_NAME = 200;
    static final int MAX_EMAIL = 255;
    static final int MAX_CODE = 50;
    static final int MAX_ADDRESS = 500;

    private final ClientRepository clients;
    private final ClientStoreScope storeScope;
    private final TransactionLockPort transactionLock;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DeriveClientFromDocumentUseCase(ClientRepository clients, ClientStoreScope storeScope,
                                           TransactionLockPort transactionLock, ApplicationEventPublisher events,
                                           Clock clock) {
        this.clients = clients;
        this.storeScope = storeScope;
        this.transactionLock = transactionLock;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Story 20.14 — an owner's derived client goes to the document's store when it is still active, else to the
     * oldest active store (or none), as before.
     */
    private UUID ownerStore(UUID documentStore, String tenantSchema) {
        if (documentStore != null) {
            try {
                return storeScope.requireActiveStore(documentStore, tenantSchema);
            } catch (com.keevo.shared.domain.exception.DomainException e) {
                log.debug("[crm] document store inactive: client derived into the oldest active store");
            }
        }
        return storeScope.storeForNewClient(ClientAccess.SYSTEM, null, tenantSchema); // oldest active, or none
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY) // joins the issue
    public Optional<UUID> execute(DocumentClientInput input, ClientAccess access, UUID actorId, String tenantSchema) {
        if (input == null || access == null) {
            return Optional.empty();
        }
        String phone = PhoneNumbers.toE164(input.phone(), PhoneNumbers.CAMEROON);
        if (phone == null) {
            log.debug("[crm] document client not derived: phone not usable");
            return Optional.empty();
        }
        UUID storeId;
        if (access.owner()) {
            storeId = ownerStore(input.storeId(), tenantSchema);
        } else {
            storeId = access.assignedStoreId();
            if (storeId == null) {
                log.debug("[crm] document client not derived: store member without store");
                return Optional.empty();
            }
        }
        transactionLock.lock("crm-document-client:" + tenantSchema + ":" + storeId + ":" + phone);
        String email = fit(validEmail(input.email()), MAX_EMAIL);
        String rccm = fit(input.rccm(), MAX_CODE);
        String niu = fit(input.niu(), MAX_CODE);
        Instant now = clock.instant();
        Optional<UUID> found = clients.findDocumentMatch(storeId, phone);
        if (found.isPresent()) {
            clients.fillMissingFromDocument(found.get(), email, rccm, niu, now);
            return found;
        }
        String name = cut(input.name(), MAX_NAME); // a long typed name is cut, never a reason to skip the client
        if (name == null) {
            log.debug("[crm] document client not derived: no name to create it with");
            return Optional.empty();
        }
        ClientProfile profile = new ClientProfile(null, niu, rccm, fit(input.address(), MAX_ADDRESS), null, null,
                null);
        Client created = clients.save(new Client(UUID.randomUUID(), name, phone, email, null, false, now, now, profile,
                storeId, MarketingConsent.NONE));
        events.publishEvent(new ClientCreatedEvent(created.id(), created.name(), actorId, tenantSchema, now));
        return Optional.of(created.id());
    }

    /** Trimmed value cut to {@code max} characters, {@code null} when blank. */
    static String cut(String value, int max) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(trimmed.length(), max)).strip();
    }

    /** Trimmed value, {@code null} when blank or longer than {@code max}. */
    static String fit(String value, int max) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() || trimmed.length() > max ? null : trimmed;
    }

    /** The {@link Client} email rule (must contain {@code @}); anything else is not copied. */
    private static String validEmail(String email) {
        return email != null && email.contains("@") ? email : null;
    }
}
