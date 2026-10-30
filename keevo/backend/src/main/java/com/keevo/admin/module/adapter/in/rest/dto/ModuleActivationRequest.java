package com.keevo.admin.module.adapter.in.rest.dto;

/**
 * Body of {@code POST /api/v1/admin/tenants/{tenantId}/modules/{moduleId}/activate} (Story 13.8a), optional.
 * Strings so that a malformed value is a field error, not an unreadable body.
 *
 * @param status    {@code ACTIVE} (default) or {@code TRIAL}
 * @param expiresAt optional end of the subscription: an ISO instant / date-time with offset, or an ISO date
 *                  {@code yyyy-MM-dd} (last covered day, end of that day in WAT); must be in the future
 */
public record ModuleActivationRequest(String status, String expiresAt) {
}
