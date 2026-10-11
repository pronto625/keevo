package com.keevo.commerce.sale.adapter.out.persistence.jpa;

import com.keevo.commerce.sale.adapter.out.persistence.entity.SaleJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaleSpringRepository extends JpaRepository<SaleJpaEntity, UUID> {

    /**
     * Loads a sale with its items in a single JOIN FETCH query.
     * Use this instead of findById() whenever items need to be mapped to the domain model.
     */
        /**
         * AC4 fix: DISTINCT prevents IncorrectResultSizeDataAccessException when a sale has
         * multiple items (JOIN FETCH creates N rows, DISTINCT collapses them to 1 entity).
         */
        @Query("SELECT DISTINCT s FROM SaleJpaEntity s LEFT JOIN FETCH s.items WHERE s.id = :id")
        Optional<SaleJpaEntity> findByIdWithItems(@Param("id") UUID id);

        @Query("SELECT DISTINCT s FROM SaleJpaEntity s JOIN s.items i WHERE s.status = 'PENDING_VALIDATION' AND i.productId = :productId")
    List<SaleJpaEntity> findPendingByProductId(@Param("productId") UUID productId);

    List<SaleJpaEntity> findByStatus(String status);

        /** AC5: EMPLOYEE-scoped pending sales — filter by store and status. */
        List<SaleJpaEntity> findByStoreIdAndStatus(UUID storeId, String status);

    // ── Story 4.4 — Sales History queries ─────────────────────────────────────

    /**
     * Find sales for a store and employee within a date range.
     * Uses occurredAt (business date) for filtering.
     */
    Page<SaleJpaEntity> findByStoreIdAndEmployeeIdAndOccurredAtBetween(
            UUID storeId, UUID employeeId, Instant from, Instant to, Pageable pageable);

    /**
     * Find all sales for a store within a date range.
     */
    Page<SaleJpaEntity> findByStoreIdAndOccurredAtBetween(
            UUID storeId, Instant from, Instant to, Pageable pageable);

    /**
     * Sales covered by a day closure — see {@link com.keevo.commerce.sale.domain.model.ClosureWindow}.
     */
    @Query("SELECT s FROM SaleJpaEntity s WHERE s.storeId = :storeId " +
           "AND s.occurredAt <= :end AND s.createdAt <= :receivedUntil " +
           "AND (s.occurredAt >= :start OR s.createdAt > :lateFrom)")
    List<SaleJpaEntity> findForClosure(@Param("storeId") UUID storeId,
                                       @Param("start") Instant start,
                                       @Param("end") Instant end,
                                       @Param("lateFrom") Instant lateFrom,
                                       @Param("receivedUntil") Instant receivedUntil);

    /**
     * Find sales for a store within a date range with a specific status.
     */
    Page<SaleJpaEntity> findByStoreIdAndOccurredAtBetweenAndStatus(
            UUID storeId, Instant from, Instant to, String status, Pageable pageable);

    /**
     * Check if sales exist for a store within a date range with given status.
     */
    boolean existsByStoreIdAndOccurredAtBetweenAndStatus(
            UUID storeId, Instant from, Instant to, String status);

    /**
     * Find all sales for a store within a date range (List version for closure summary).
     */
    List<SaleJpaEntity> findAllByStoreIdAndOccurredAtBetween(UUID storeId, Instant from, Instant to);

    /**
     * Compare-and-set status change in one statement: of two concurrent callers expecting the
     * same status, exactly one gets 1. Bumps updated_at so the delta pull carries the change.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SaleJpaEntity s SET s.status = :newStatus, s.updatedAt = :now " +
           "WHERE s.id = :id AND s.status = :expected")
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("expected") String expected,
                              @Param("newStatus") String newStatus,
                              @Param("now") Instant now);
}
