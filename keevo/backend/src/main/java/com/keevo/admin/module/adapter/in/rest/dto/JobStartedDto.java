package com.keevo.admin.module.adapter.in.rest.dto;

import java.util.UUID;

/** Answer of a request that starts a background job (Story 16.4): follow it with {@code GET /api/v1/admin/jobs/{jobId}}. */
public record JobStartedDto(UUID jobId) {
}
