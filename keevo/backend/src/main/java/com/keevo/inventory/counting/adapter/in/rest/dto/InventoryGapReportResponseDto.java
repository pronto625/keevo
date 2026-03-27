package com.keevo.inventory.counting.adapter.in.rest.dto;

import com.keevo.inventory.counting.domain.model.InventoryGapReport;
import com.keevo.inventory.counting.domain.model.InventoryGapSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO for the full gap analysis report response.
 * Story 6.3 — Gap Analysis Report.
 */
public record InventoryGapReportResponseDto(
        UUID sessionId,
        UUID storeId,
        String storeName,
        String scope,
        InventoryGapSummaryDto summary,
        List<InventoryGapRowDto> concordantRows,
        List<InventoryGapRowDto> surplusRows,
        List<InventoryGapRowDto> shortageRows,
        Instant generatedAt
) {
    public static InventoryGapReportResponseDto fromDomain(InventoryGapReport report) {
        return new InventoryGapReportResponseDto(
                report.getSessionId(),
                report.getStoreId(),
                report.getStoreName(),
                report.getScope().name(),
                InventoryGapSummaryDto.fromDomain(report.getSummary()),
                report.getConcordantRows().stream().map(InventoryGapRowDto::fromDomain).toList(),
                report.getSurplusRows().stream().map(InventoryGapRowDto::fromDomain).toList(),
                report.getShortageRows().stream().map(InventoryGapRowDto::fromDomain).toList(),
                report.getGeneratedAt()
        );
    }

    public record InventoryGapSummaryDto(
            int totalCounted,
            int totalConcordant,
            int totalSurplus,
            int totalShortage,
            long totalSurplusValueXaf,
            long totalShortageValueXaf
    ) {
        public static InventoryGapSummaryDto fromDomain(InventoryGapSummary s) {
            return new InventoryGapSummaryDto(
                    s.totalCounted(), s.totalConcordant(), s.totalSurplus(), s.totalShortage(),
                    s.totalSurplusValueXaf(), s.totalShortageValueXaf()
            );
        }
    }
}
