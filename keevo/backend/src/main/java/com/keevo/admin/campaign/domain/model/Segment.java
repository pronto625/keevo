package com.keevo.admin.campaign.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — the companies a platform campaign reaches (all criteria optional and cumulative): their plan codes, the
 * country / activity (category) of one of their active stores, or an explicit list of companies. Empty = every live
 * company (never suspended or deleted ones, never the platform's own account).
 */
public record Segment(List<String> planCodes, List<String> countries, List<String> activities, List<UUID> tenantIds) {

    public Segment {
        planCodes = planCodes == null ? List.of() : List.copyOf(planCodes);
        countries = countries == null ? List.of() : List.copyOf(countries);
        activities = activities == null ? List.of() : List.copyOf(activities);
        tenantIds = tenantIds == null ? List.of() : List.copyOf(tenantIds);
    }

    public static final Segment ALL = new Segment(null, null, null, null);

    /** Country or activity filters need the companies' stores (tenant schemas). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean needsStores() {
        return !countries.isEmpty() || !activities.isEmpty();
    }
}
