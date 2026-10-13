package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.AdoptionReport;

import java.util.List;

/** Contract with keevo/dashboard/src/types/analytics.ts (AdoptionReport); feature labelled by the dashboard. */
public record AdoptionReportDto(long activeTenants, List<Feature> features) {

    public record Feature(String feature, long tenantCount, double rate) {}

    public static AdoptionReportDto from(AdoptionReport r) {
        return new AdoptionReportDto(r.activeTenants(), r.features().stream()
                .map(f -> new Feature(f.feature(), f.tenantCount(), f.rate()))
                .toList());
    }
}
