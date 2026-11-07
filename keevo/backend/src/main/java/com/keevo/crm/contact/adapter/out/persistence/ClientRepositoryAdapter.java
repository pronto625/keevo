package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.adapter.out.persistence.entity.ClientJpaEntity;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.crm.contact.domain.service.ClientVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** Story 20.6 — the keyset page query (built per call: only the predicates in use, so the indexes apply). */
    private final EntityManager entityManager;

    public ClientRepositoryAdapter(ClientSpringRepository springRepository) {
        this(springRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ClientRepositoryAdapter(ClientSpringRepository springRepository, EntityManager entityManager) {
        this.springRepository = springRepository;
        this.entityManager = entityManager;
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
        return springRepository.searchByNameOrPhone(containsPattern(query))
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
        return springRepository.searchScoped(containsPattern(query), storeIds(scope))
                .stream().map(this::toDomain).toList();
    }

    /**
     * Story 20.6 — {@code %query%}: the shape the trigram indexes serve ({@code LIKE} on a constant pattern). The
     * user's {@code \\}, {@code %} and {@code _} are escaped (PostgreSQL's default {@code LIKE} escape is the
     * backslash): a search for « _ » finds an underscore, not every client.
     */
    static String containsPattern(String query) {
        String q = query == null ? "" : query;
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    public Optional<UUID> findDocumentMatch(UUID storeId, String phoneNormalized) {
        if (phoneNormalized == null) return Optional.empty();
        List<String> ids = storeId == null
                ? springRepository.findDocumentMatchWithoutStore(phoneNormalized)
                : springRepository.findDocumentMatchInStore(storeId, phoneNormalized);
        return ids.stream().findFirst().map(UUID::fromString);
    }

    @Override
    public void fillMissingFromDocument(UUID id, String email, String rccm, String niu, Instant now) {
        if (email == null && rccm == null && niu == null) return;
        springRepository.fillMissingFromDocument(id, email == null ? "" : email, rccm == null ? "" : rccm,
                niu == null ? "" : niu, now);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Client> findPage(String search, boolean includeArchived, ClientVisibility.Scope scope,
                                 ClientKeyset after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM clients c WHERE ");
        Map<String, Object> params = new LinkedHashMap<>();
        boolean searching = search != null && !search.isBlank();
        if (searching) {
            // same rule as searchByNameOrPhone: non-archived only (includeArchived ignored, as before 20.6)
            sql.append("c.archived = FALSE AND (lower(c.name) LIKE lower(:pattern) OR c.phone LIKE :pattern)");
            params.put("pattern", containsPattern(search.strip()));
        } else {
            sql.append(includeArchived ? "TRUE" : "c.archived = FALSE");
        }
        if (scope.restricted()) {
            sql.append(" AND (c.store_id IS NULL OR c.store_id = ANY(CAST(:storeIds AS uuid[])))");
            params.put("storeIds", arrayLiteral(storeIds(scope)));
        }
        if (after != null) {
            sql.append(" AND (lower(c.name), c.id) > (lower(:afterName), CAST(:afterId AS uuid))");
            params.put("afterName", after.name());
            params.put("afterId", after.id().toString());
        }
        sql.append(" ORDER BY lower(c.name), c.id LIMIT :limit");
        params.put("limit", limit);
        Query query = entityManager.createNativeQuery(sql.toString(), ClientJpaEntity.class);
        params.forEach(query::setParameter);
        return ((List<ClientJpaEntity>) query.getResultList()).stream().map(this::toDomain).toList();
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
