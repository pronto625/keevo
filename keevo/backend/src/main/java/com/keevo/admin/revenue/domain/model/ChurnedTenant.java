package com.keevo.admin.revenue.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A tenant lost in a month (Story 9-3).
 *
 * @param tenantName   business name, null when unnamed (labelled « Sans nom » by the adapters)
 * @param planEndDate  last day of the plan (EXPIRY: event date − 1; ADMIN_DOWNGRADE: event date), WAT
 * @param tenureMonths months from the start of the latest continuous paid streak to the plan end (1 decimal),
 *                     null when the tenant has no recorded payment
 * @param lastGmv30d   COMPLETED sales of the 30 days ending on the plan end date (XAF), 0 when unreadable
 */
public record ChurnedTenant(UUID tenantId, String tenantName, LocalDate planEndDate, Double tenureMonths, long lastGmv30d) {}
