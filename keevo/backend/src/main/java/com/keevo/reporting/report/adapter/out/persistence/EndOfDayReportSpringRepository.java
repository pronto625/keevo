package com.keevo.reporting.report.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EndOfDayReportSpringRepository — Spring Data JPA repository for reports table.
 * Story 7.2 — Rapport End-of-Day
 */
public interface EndOfDayReportSpringRepository
        extends JpaRepository<EndOfDayReportJpaEntity, UUID> {

    Page<EndOfDayReportJpaEntity> findByReportTypeAndTenantIdOrderByReportDateDesc(
            String reportType, String tenantId, Pageable pageable);

    /**
     * Flexible filtered query — null params = no filter applied.
     */
    @Query("SELECT r FROM EndOfDayReportJpaEntity r WHERE " +
           "r.tenantId = :tenantId " +
           "AND (:storeId IS NULL OR CAST(r.storeId AS string) = :storeId) " +
           "AND (:actorId IS NULL OR CAST(r.actorId AS string) = :actorId) " +
           "AND (:type IS NULL OR r.reportType = :type) " +
           "ORDER BY r.reportDate DESC")
    Page<EndOfDayReportJpaEntity> findFiltered(
            @Param("tenantId") String tenantId,
            @Param("storeId") String storeId,
            @Param("actorId") String actorId,
            @Param("type") String type,
            Pageable pageable);

    /**
     * FAILED deliveries, plus PENDING reports created before {@code stalePendingBefore}: since
     * v1s-13-13 a weekly report commits as PENDING and is sent after commit, so a crash between
     * the two would otherwise leave it PENDING for ever (the "already generated" guard blocks a
     * regeneration). Every other path writes the final status in the creating transaction.
     */
    @Query("SELECT r FROM EndOfDayReportJpaEntity r WHERE r.deliveryAttempts < :maxAttempts AND "
            + "(r.deliveryStatus = 'FAILED' OR (r.deliveryStatus = 'PENDING' AND r.createdAt < :stalePendingBefore))")
    List<EndOfDayReportJpaEntity> findPendingRetries(@Param("maxAttempts") int maxAttempts,
                                                     @Param("stalePendingBefore") java.time.Instant stalePendingBefore);

    List<EndOfDayReportJpaEntity> findByReportDateAndTenantIdAndReportType(
            LocalDate reportDate, String tenantId, String reportType);

    @Query(value = "SELECT COUNT(DISTINCT store_id) FROM day_closures " +
                   "WHERE DATE(closed_at) = :date AND tenant_id = :tenantId",
           nativeQuery = true)
    int countDistinctStoresClosed(@Param("date") LocalDate date, @Param("tenantId") String tenantId);

    /**
     * Idempotency lookup: finds the existing report for an exact
     * (storeId, reportDate, reportType, actorId) key. Handles actorId=null for store-level reports.
     */
    @Query("SELECT r FROM EndOfDayReportJpaEntity r WHERE " +
           "r.storeId = :storeId AND r.reportDate = :reportDate AND r.reportType = :reportType " +
           "AND ((:actorId IS NULL AND r.actorId IS NULL) OR r.actorId = :actorId)")
    Optional<EndOfDayReportJpaEntity> findByKey(
            @Param("storeId") UUID storeId,
            @Param("reportDate") LocalDate reportDate,
            @Param("reportType") String reportType,
            @Param("actorId") UUID actorId);
}
