package com.keevo.admin.job.adapter.in.rest.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.shared.job.AdminJobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A super-admin background job (Story 16.4): kind and its French label, parameters, status, progress, the final
 * report (same shape as the operation's own report, tenants by code) and the French error of a failed job. The actor
 * is not exposed.
 */
public record AdminJobDto(UUID id, String kind, String label, Map<String, Object> params, String status,
                          int processed, Integer total, JsonNode report, Instant createdAt, Instant startedAt,
                          Instant finishedAt, String error) {

    public static AdminJobDto from(AdminJobs.JobView job) {
        return new AdminJobDto(job.id(), job.kind().name(), job.kind().label(), job.params(), job.status().name(),
                job.processed(), job.total(), job.report(), job.createdAt(), job.startedAt(), job.finishedAt(),
                job.error());
    }
}
