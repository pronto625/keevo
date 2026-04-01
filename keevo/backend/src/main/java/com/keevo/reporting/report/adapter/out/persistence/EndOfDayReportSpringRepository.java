package com.keevo.reporting.report.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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

    @Query("SELECT r FROM EndOfDayReportJpaEntity r WHERE r.deliveryStatus = 'FAILED' AND r.deliveryAttempts < :maxAttempts")
    List<EndOfDayReportJpaEntity> findPendingRetries(@Param("maxAttempts") int maxAttempts);

    List<EndOfDayReportJpaEntity> findByReportDateAndTenantIdAndReportType(
            LocalDate reportDate, String tenantId, String reportType);

    @Query(value = "SELECT COUNT(DISTINCT store_id) FROM day_closures " +
                   "WHERE DATE(closed_at) = :date AND tenant_id = :tenantId",
           nativeQuery = true)
    int countDistinctStoresClosed(@Param("date") LocalDate date, @Param("tenantId") String tenantId);
}
