package com.keevo.reporting.report.adapter.in.rest.dto;

import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ReportResponseDto — REST DTO for a single EndOfDayReport.
 * Story 7.2 — Task 8.2 | Updated: actorId for multi-vendor support.
 */
public record ReportResponseDto(
        UUID id,
        String tenantId,
        UUID storeId,
        String storeName,
        UUID actorId,
        ReportType reportType,
        LocalDate reportDate,
        String content,
        DeliveryStatus deliveryStatus,
        int deliveryAttempts,
        Instant lastAttemptAt,
        int totalRevenue,
        int totalSales,
        boolean isAutomatic,
        Instant createdAt
) {
    public static ReportResponseDto from(EndOfDayReport r) {
        return new ReportResponseDto(
                r.getId(), r.getTenantId(), r.getStoreId(), r.getStoreName(),
                r.getActorId(),
                r.getReportType(), r.getReportDate(), r.getContent(),
                r.getDeliveryStatus(), r.getDeliveryAttempts(), r.getLastAttemptAt(),
                r.getTotalRevenue(), r.getTotalSales(), r.isAutomatic(), r.getCreatedAt()
        );
    }
}
