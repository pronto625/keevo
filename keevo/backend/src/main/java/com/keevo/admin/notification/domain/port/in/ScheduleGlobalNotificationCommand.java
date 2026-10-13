package com.keevo.admin.notification.domain.port.in;

import java.util.UUID;

/**
 * Raw input of « Envoyer » (validated by the service → 422 VALIDATION_ERROR).
 *
 * @param target         ALL | PREMIUM | TRIAL | FREE | TENANT (case-insensitive)
 * @param targetTenantId company id, required iff target is TENANT
 * @param scheduledAt    ISO-8601 instant/offset date-time; null or blank = now
 */
public record ScheduleGlobalNotificationCommand(String title, String body, String target, String targetTenantId,
                                                String scheduledAt, UUID actorId) {}
