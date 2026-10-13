package com.keevo.admin.platform.adapter.in.rest.dto;

import com.keevo.admin.platform.domain.model.SystemHealth;

import java.util.List;

/**
 * SystemHealthDto — {@code GET /api/v1/admin/health} (Story 9-5). Every metric has its
 * {@code …Breached} flag; null values are « not measurable yet » (no sample, database down).
 * {@code breachedMetrics} holds the French labels of the breached metrics.
 */
public record SystemHealthDto(
        Double uptimePercent30d,
        boolean uptimeBreached,
        Integer p50Ms,
        Integer p95Ms,
        Integer p99Ms,
        boolean latencyBreached,
        Double errorRatePercent1h,
        boolean errorRateBreached,
        Integer dbActiveConnections,
        Integer dbMaxConnections,
        boolean dbActiveConnectionsBreached,
        boolean databaseOk,
        boolean databaseBreached,
        Long syncRefusals1h,
        boolean syncRefusalsBreached,
        String lastSampleAt,
        String checkedAt,
        boolean incident,
        List<String> breachedMetrics
) {
    public static SystemHealthDto from(SystemHealth h) {
        return new SystemHealthDto(
                h.uptimePercent30d(), h.uptimeBreached(),
                h.p50Ms(), h.p95Ms(), h.p99Ms(), h.latencyBreached(),
                h.errorRatePercent1h(), h.errorRateBreached(),
                h.dbActiveConnections(), h.dbMaxConnections(), h.dbActiveConnectionsBreached(),
                h.databaseOk(), h.databaseBreached(),
                h.syncRefusals1h(), h.syncRefusalsBreached(),
                h.lastSampleAt() != null ? h.lastSampleAt().toString() : null,
                h.checkedAt().toString(),
                h.incident(),
                HealthStatusDto.labels(h));
    }
}
