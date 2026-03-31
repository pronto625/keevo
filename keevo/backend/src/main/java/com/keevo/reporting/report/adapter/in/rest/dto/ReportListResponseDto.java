package com.keevo.reporting.report.adapter.in.rest.dto;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * ReportListResponseDto — paginated list DTO for report history.
 * Story 7.2 — Task 8.2.
 */
public record ReportListResponseDto(
        List<ReportResponseDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ReportListResponseDto from(Page<EndOfDayReport> page) {
        return new ReportListResponseDto(
                page.getContent().stream().map(ReportResponseDto::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
