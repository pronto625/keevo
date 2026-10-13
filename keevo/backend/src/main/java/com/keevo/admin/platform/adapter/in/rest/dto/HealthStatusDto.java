package com.keevo.admin.platform.adapter.in.rest.dto;

import com.keevo.admin.platform.domain.model.SystemHealth;

import java.util.List;

/**
 * HealthStatusDto — {@code GET /api/v1/admin/health/status}, polled every 60 s by the dashboard
 * layout for the sticky incident banner (Story 9-5). {@code breachedMetrics} are French labels.
 */
public record HealthStatusDto(boolean incident, List<String> breachedMetrics) {

    public static HealthStatusDto from(SystemHealth h) {
        return new HealthStatusDto(h.incident(), labels(h));
    }

    static List<String> labels(SystemHealth h) {
        return h.breachedMetrics().stream().map(SystemHealth.Metric::label).toList();
    }
}
