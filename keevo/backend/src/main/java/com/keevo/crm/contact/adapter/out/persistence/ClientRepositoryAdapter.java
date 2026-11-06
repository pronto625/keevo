package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.adapter.out.persistence.entity.ClientJpaEntity;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.crm.contact.domain.service.ClientVisibility;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ClientRepositoryAdapter — JPA implementation of {@link ClientRepository} (Story 2.5; Story 20.2 — Bref fields,
 * store, consent, visibility rule).
 */
@Component
public class ClientRepositoryAdapter implements ClientRepository {

    /** Pads an empty visible set: {@code IN ()} is not valid SQL, and no store has the nil id. */
    private static final Set<UUID> NO_STORE = Set.of(new UUID(0L, 0L));

    private final ClientSpringRepository springRepository;

    public ClientRepositoryAdapter(ClientSpringRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Client save(Client client) {
        return toDomain(springRepository.save(toEntity(client)), client.consent().optOutAt());
    }

    @Override
    public Optional<Client> findById(UUID id) {
        return springRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Client> findAll(boolean includeArchived) {
        return springRepository.findAllFiltered(includeArchived)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> searchByNameOrPhone(String query) {
        return springRepository.searchByNameOrPhone(query)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> findAll(boolean includeArchived, ClientVisibility.Scope scope) {
        if (!scope.restricted()) return findAll(includeArchived);
        return springRepository.findAllScoped(includeArchived, storeIds(scope))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> searchByNameOrPhone(String query, ClientVisibility.Scope scope) {
        if (!scope.restricted()) return searchByNameOrPhone(query);
        return springRepository.searchScoped(query, storeIds(scope))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> findActiveByStoreAndPhone(UUID storeId, String phoneNormalized) {
        if (storeId == null || phoneNormalized == null) return List.of();
        return springRepository.findActiveByStoreAndPhone(storeId, phoneNormalized)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Client> findActiveByStoresAndPhone(Collection<UUID> storeIds, String phoneNormalized) {
        if (storeIds == null || storeIds.isEmpty() || phoneNormalized == null) return List.of();
        return springRepository.findActiveByStoresAndPhone(arrayLiteral(storeIds), phoneNormalized)
                .stream().map(this::toDomain).toList();
    }

    /** {@code {id1,id2}} — UUIDs only, bound as one parameter and cast to {@code uuid[]}. */
    private static String arrayLiteral(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    @Override
    public Set<UUID> findMarketingEligible(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Set.of();
        String literal = arrayLiteral(ids);
        Set<UUID> eligible = new HashSet<>();
        for (String id : springRepository.findMarketingEligibleIds(literal)) eligible.add(UUID.fromString(id));
        return eligible;
    }

    @Override
    public void updateConsent(UUID id, MarketingConsent consent, Instant now) {
        springRepository.updateConsent(id, consent.consentAt(), consent.consentBy(), now);
    }

    @Override
    public void archive(UUID id) {
        springRepository.findById(id).ifPresent(e -> {
            e.setArchived(true);
            e.setUpdatedAt(Instant.now());
            springRepository.save(e);
        });
    }

    @Override
    public void unarchive(UUID id) {
        springRepository.findById(id).ifPresent(e -> {
            e.setArchived(false);
            e.setUpdatedAt(Instant.now()); // pull cursor: the other devices get the restored client
            springRepository.save(e);
        });
    }

    private static Collection<UUID> storeIds(ClientVisibility.Scope scope) {
        return scope.visibleStoreIds().isEmpty() ? NO_STORE : scope.visibleStoreIds();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ClientJpaEntity toEntity(Client client) {
        ClientJpaEntity e = new ClientJpaEntity(
                client.id(), client.name(), client.phone(), client.email(),
                client.notes(), client.archived(), client.createdAt(), client.updatedAt());
        ClientProfile p = client.profile();
        e.setClientType(p.type() == null ? null : p.type().name());
        e.setNiu(p.niu());
        e.setRccm(p.rccm());
        e.setAddress(p.address());
        e.setContactName(p.contactName());
        e.setWebsite(p.website());
        e.setBirthDate(p.birthDate());
        e.setStoreId(client.storeId());
        e.setPhoneNormalized(client.phoneNormalized());
        e.setMarketingConsentAt(client.consent().consentAt());
        e.setMarketingConsentBy(client.consent().consentBy());
        // marketing_opt_out_at is read-only for JPA (written by the inbound STOP handling, 21.5)
        return e;
    }

    private Client toDomain(ClientJpaEntity e) {
        return toDomain(e, e.getMarketingOptOutAt());
    }

    /** {@code optOutAt}: the merged entity of a save does not carry the read-only column, the saved client does. */
    private Client toDomain(ClientJpaEntity e, Instant optOutAt) {
        ClientProfile profile = new ClientProfile(
                ClientType.parse(e.getClientType()).orElse(null), // an unknown stored value reads as none
                e.getNiu(), e.getRccm(), e.getAddress(), e.getContactName(), e.getWebsite(), e.getBirthDate());
        MarketingConsent consent = new MarketingConsent(e.getMarketingConsentAt(), e.getMarketingConsentBy(),
                optOutAt);
        return new Client(e.getId(), e.getName(), e.getPhone(), e.getEmail(),
                e.getNotes(), e.getArchived(), e.getCreatedAt(), e.getUpdatedAt(), profile, e.getStoreId(), consent);
    }
}
