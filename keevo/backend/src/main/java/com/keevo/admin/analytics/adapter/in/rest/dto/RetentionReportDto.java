package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.RetentionReport;

import java.time.LocalDate;
import java.util.List;

/** Contract with keevo/dashboard/src/types/analytics.ts (RetentionReport). */
public record RetentionReportDto(int weeks, List<Cohort> cohorts) {

    public record Cohort(LocalDate weekStart, int tenantCount, List<Double> retention) {}

    public static RetentionReportDto from(RetentionReport r) {
        return new RetentionReportDto(r.weeks(), r.cohorts().stream()
                .map(c -> new Cohort(c.weekStart(), c.tenantCount(), c.retention()))
                .toList());
    }
}
