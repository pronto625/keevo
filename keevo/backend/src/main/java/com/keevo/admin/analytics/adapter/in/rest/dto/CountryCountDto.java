package com.keevo.admin.analytics.adapter.in.rest.dto;

import com.keevo.admin.analytics.domain.model.CountryCount;

/** Contract with keevo/dashboard/src/types/analytics.ts (CountryCount); country labelled by the dashboard. */
public record CountryCountDto(String country, long tenantCount) {
    public static CountryCountDto from(CountryCount c) {
        return new CountryCountDto(c.country(), c.tenantCount());
    }
}
