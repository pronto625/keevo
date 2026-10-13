package com.keevo.admin.notification.adapter.in.rest.dto;

/**
 * Body of {@code POST /api/v1/admin/notifications/global}. Plain strings: every check (lengths, target,
 * company, date window) is done by the service → 422 VALIDATION_ERROR with French field messages.
 *
 * @param scheduledAt ISO-8601 date-time with offset; null = now
 */
public record GlobalNotificationRequestDto(String title, String body, String target, String targetTenantId,
                                           String scheduledAt) {}
