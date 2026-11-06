package com.keevo.crm.contact.domain.port.out;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.service.ClientVisibility;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ClientRepository — output port for client persistence (Hexagonal Architecture).
 *
 * <p>Isolates domain logic from JDBC/JPA details. Implementation lives in
 * {@code crm/contact/adapter/out/persistence/}.
 */
public interface ClientRepository {

    Client save(Client client);

    Optional<Client> findById(UUID id);

    /** Every client of the tenant (unrestricted). */
    List<Client> findAll(boolean includeArchived);

    /** Every non-archived client whose name or phone matches (unrestricted). */
    List<Client> searchByNameOrPhone(String query);

    /** Story 20.2 — {@link #findAll(boolean)} limited to what {@code scope} may see. */
    List<Client> findAll(boolean includeArchived, ClientVisibility.Scope scope);

    /** Story 20.2 — {@link #searchByNameOrPhone(String)} limited to what {@code scope} may see. */
    List<Client> searchByNameOrPhone(String query, ClientVisibility.Scope scope);

    /** Story 20.2 — non-archived clients of {@code storeId} with this E.164 phone, oldest first. */
    List<Client> findActiveByStoreAndPhone(UUID storeId, String phoneNormalized);

    /** Story 20.2 — non-archived clients of any of {@code storeIds} with this E.164 phone, oldest first. */
    List<Client> findActiveByStoresAndPhone(Collection<UUID> storeIds, String phoneNormalized);

    /** Story 20.2 — the ids among {@code ids} eligible to marketing (consent, no STOP, not archived). */
    Set<UUID> findMarketingEligible(Collection<UUID> ids);

    /** Story 20.2 — writes only the consent columns (and {@code updated_at}) of a client. */
    void updateConsent(UUID id, com.keevo.crm.contact.domain.entity.MarketingConsent consent, java.time.Instant now);

    void archive(UUID id);

    void unarchive(UUID id);
}
