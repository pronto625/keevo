package com.keevo.crm.contact.adapter.out.persistence;

import com.keevo.crm.contact.adapter.out.persistence.entity.ClientJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * ClientSpringRepository — Spring Data JPA interface for {@code ClientJpaEntity}.
 *
 * <p>Story 20.2 — the {@code Scoped} queries apply the client visibility rule (ADR-08):
 * {@code store_id IS NULL OR store_id IN (:storeIds)} ({@code storeIds} never empty: the adapter pads it), served by
 * {@code idx_clients_store}.
 */
public interface ClientSpringRepository extends JpaRepository<ClientJpaEntity, UUID> {

    @Query("SELECT c FROM ClientJpaEntity c WHERE (:includeArchived = TRUE OR c.archived = FALSE) ORDER BY c.name")
    List<ClientJpaEntity> findAllFiltered(@Param("includeArchived") boolean includeArchived);

    /**
     * Story 20.6 — {@code pattern} = {@code %query%} built by the adapter: {@code lower(name) LIKE lower(:pattern)} and
     * {@code phone LIKE :pattern} are served by the trigram indexes of crm V3 (no sequential scan). {@code ESCAPE '\'} is
     * explicit: without it Hibernate 6 renders {@code escape ''} on PostgreSQL and the adapter's escaped {@code _ % \}
     * would match nothing.
     */
    @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE AND " +
           "(LOWER(c.name) LIKE LOWER(:pattern) ESCAPE '\\' OR c.phone LIKE :pattern ESCAPE '\\')")
    List<ClientJpaEntity> searchByNameOrPhone(@Param("pattern") String pattern);

    @Query("SELECT c FROM ClientJpaEntity c WHERE (:includeArchived = TRUE OR c.archived = FALSE) " +
           "AND (c.storeId IS NULL OR c.storeId IN :storeIds) ORDER BY c.name")
    List<ClientJpaEntity> findAllScoped(@Param("includeArchived") boolean includeArchived,
                                        @Param("storeIds") Collection<UUID> storeIds);

    @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE " +
           "AND (c.storeId IS NULL OR c.storeId IN :storeIds) AND " +
           "(LOWER(c.name) LIKE LOWER(:pattern) ESCAPE '\\' OR c.phone LIKE :pattern ESCAPE '\\')")
    List<ClientJpaEntity> searchScoped(@Param("pattern") String pattern, @Param("storeIds") Collection<UUID> storeIds);

    /** Story 20.2 — same-store duplicate probe ({@code idx_clients_store_phone}), oldest first. */
    @Query("SELECT c FROM ClientJpaEntity c WHERE c.storeId = :storeId AND c.phoneNormalized = :phone " +
           "AND c.archived = FALSE ORDER BY c.createdAt, c.id")
    List<ClientJpaEntity> findActiveByStoreAndPhone(@Param("storeId") UUID storeId, @Param("phone") String phone);

    /** Story 20.2 — duplicate probe over several stores ({@code idx_clients_store_phone} per store). */
    @Query(value = "SELECT * FROM clients WHERE store_id = ANY(CAST(:storeIds AS uuid[])) "
            + "AND phone_normalized = :phone AND archived = FALSE ORDER BY created_at, id", nativeQuery = true)
    List<ClientJpaEntity> findActiveByStoresAndPhone(@Param("storeIds") String storeIdsArrayLiteral,
                                                     @Param("phone") String phone);

    /**
     * Story 20.2 — eligibility for campaigns (21.6), one query for a set of ids bound as one array parameter
     * ({@code {id,…}}: one plan whatever the size, no bind-parameter limit).
     */
    @Query(value = "SELECT CAST(id AS text) FROM clients WHERE id = ANY(CAST(:ids AS uuid[])) AND archived = FALSE "
            + "AND marketing_consent_at IS NOT NULL AND marketing_opt_out_at IS NULL", nativeQuery = true)
    List<String> findMarketingEligibleIds(@Param("ids") String idsArrayLiteral);

    /**
     * Story 20.6 — the client a document's phone stands for in {@code storeId}: a non-archived client of that store,
     * else a legacy one (no store); several → the oldest, then the smallest id. Served by
     * {@code idx_clients_store_phone} (one probe per branch).
     */
    @Query(value = "SELECT CAST(id AS text) FROM clients WHERE phone_normalized = :phone AND archived = FALSE "
            + "AND (store_id = :storeId OR store_id IS NULL) "
            + "ORDER BY (store_id IS NULL), created_at, id LIMIT 1", nativeQuery = true)
    List<String> findDocumentMatchInStore(@Param("storeId") UUID storeId, @Param("phone") String phone);

    /** Story 20.6 — {@link #findDocumentMatchInStore} for an actor without store: the legacy clients only. */
    @Query(value = "SELECT CAST(id AS text) FROM clients WHERE phone_normalized = :phone AND archived = FALSE "
            + "AND store_id IS NULL ORDER BY created_at, id LIMIT 1", nativeQuery = true)
    List<String> findDocumentMatchWithoutStore(@Param("phone") String phone);

    /**
     * Story 20.6 — fills only the empty email / RCCM / NIU of a client from a document (Bref's {@code COALESCE}); an
     * empty string means « nothing to give ». Touches the row (and {@code updated_at}, for the pull) only when a value
     * actually changes; never clears the persistence context (other entities of the caller stay managed).
     */
    @Modifying
    @Query(value = "UPDATE clients SET email = COALESCE(email, NULLIF(:email, '')), "
            + "rccm = COALESCE(rccm, NULLIF(:rccm, '')), niu = COALESCE(niu, NULLIF(:niu, '')), updated_at = :now "
            + "WHERE id = :id AND ((email IS NULL AND :email <> '') OR (rccm IS NULL AND :rccm <> '') "
            + "OR (niu IS NULL AND :niu <> ''))", nativeQuery = true)
    int fillMissingFromDocument(@Param("id") UUID id, @Param("email") String email, @Param("rccm") String rccm,
                                @Param("niu") String niu, @Param("now") Instant now);

    /**
     * Story 20.2 — the only write of the consent columns (mapped {@code updatable = false}): a concurrent generic
     * update of the client can never revert a withdrawal.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE clients SET marketing_consent_at = :at, marketing_consent_by = :by, updated_at = :now "
            + "WHERE id = :id", nativeQuery = true)
    int updateConsent(@Param("id") UUID id, @Param("at") Instant at, @Param("by") UUID by,
                      @Param("now") Instant now);

    /**
     * Story 21.6 — the campaign audience: eligible clients, one per phone (most recently updated first), every store
     * when {@code allStores}, else those of {@code storeIds} ({@code {id,…}} literal). Rows: id, name, phone, store.
     */
    @Query(value = "SELECT CAST(id AS text) AS id, name, phone_normalized, CAST(store_id AS text) AS store_id FROM ("
            + "SELECT DISTINCT ON (phone_normalized) id, name, phone_normalized, store_id FROM clients "
            + "WHERE marketing_consent_at IS NOT NULL AND marketing_opt_out_at IS NULL AND archived = FALSE "
            + "AND phone_normalized IS NOT NULL "
            + "AND (:allStores = TRUE OR store_id = ANY(CAST(:storeIds AS uuid[]))) "
            + "ORDER BY phone_normalized, updated_at DESC, id) a ORDER BY phone_normalized LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findMarketingAudience(@Param("allStores") boolean allStores, @Param("storeIds") String storeIds,
                                         @Param("limit") int limit);

    /** Story 21.6 — the audience size: distinct eligible phones, up to {@code cap} (rows never loaded). */
    @Query(value = "SELECT CAST(count(*) AS int) FROM (SELECT DISTINCT phone_normalized FROM clients WHERE marketing_consent_at "
            + "IS NOT NULL AND marketing_opt_out_at IS NULL AND archived = FALSE AND phone_normalized IS NOT NULL "
            + "AND (:allStores = TRUE OR store_id = ANY(CAST(:storeIds AS uuid[]))) LIMIT :cap) a", nativeQuery = true)
    int countMarketingAudience(@Param("allStores") boolean allStores, @Param("storeIds") String storeIds,
                               @Param("cap") int cap);

    /** Story 21.6 — a STOP: the clients of this number not opted out yet (an earlier STOP keeps its date). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE clients SET marketing_opt_out_at = :at, updated_at = :at "
            + "WHERE phone_normalized = :phone AND marketing_opt_out_at IS NULL", nativeQuery = true)
    int optOutByPhone(@Param("phone") String phone, @Param("at") Instant at);

    /** Story 21.6 — a START: the opt-out of this number cleared. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE clients SET marketing_opt_out_at = NULL, updated_at = :at "
            + "WHERE phone_normalized = :phone AND marketing_opt_out_at IS NOT NULL", nativeQuery = true)
    int optInByPhone(@Param("phone") String phone, @Param("at") Instant at);
}
