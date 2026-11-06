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

    @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE AND " +
           "(LOWER(c.name) LIKE LOWER(CONCAT('%',:q,'%')) OR c.phone LIKE CONCAT('%',:q,'%'))")
    List<ClientJpaEntity> searchByNameOrPhone(@Param("q") String query);

    @Query("SELECT c FROM ClientJpaEntity c WHERE (:includeArchived = TRUE OR c.archived = FALSE) " +
           "AND (c.storeId IS NULL OR c.storeId IN :storeIds) ORDER BY c.name")
    List<ClientJpaEntity> findAllScoped(@Param("includeArchived") boolean includeArchived,
                                        @Param("storeIds") Collection<UUID> storeIds);

    @Query("SELECT c FROM ClientJpaEntity c WHERE c.archived = FALSE " +
           "AND (c.storeId IS NULL OR c.storeId IN :storeIds) AND " +
           "(LOWER(c.name) LIKE LOWER(CONCAT('%',:q,'%')) OR c.phone LIKE CONCAT('%',:q,'%'))")
    List<ClientJpaEntity> searchScoped(@Param("q") String query, @Param("storeIds") Collection<UUID> storeIds);

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
     * Story 20.2 — the only write of the consent columns (mapped {@code updatable = false}): a concurrent generic
     * update of the client can never revert a withdrawal.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE clients SET marketing_consent_at = :at, marketing_consent_by = :by, updated_at = :now "
            + "WHERE id = :id", nativeQuery = true)
    int updateConsent(@Param("id") UUID id, @Param("at") Instant at, @Param("by") UUID by,
                      @Param("now") Instant now);
}
