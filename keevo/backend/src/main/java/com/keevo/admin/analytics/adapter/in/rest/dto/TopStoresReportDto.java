package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.TopStoresReport;

import java.time.LocalDate;
import java.util.List;

/** Contract with keevo/dashboard/src/types/analytics.ts (TopStoresReport). No store / tenant id. */
public record TopStoresReportDto(LocalDate from, LocalDate to, List<Store> stores) {

    public record Store(String storeName, String tenantName, long gmv, long transactions, long averageBasket,
                        Double growthPercent) {}

    public static TopStoresReportDto from(TopStoresReport r) {
        return new TopStoresReportDto(r.from(), r.to(), r.stores().stream()
                .map(s -> new Store(s.storeName(), s.tenantName(), s.gmv(), s.transactions(), s.averageBasket(),
                        s.growthPercent()))
                .toList());
    }
}
