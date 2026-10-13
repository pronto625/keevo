package com.keevo.admin.analytics.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Tenants who signed up during one ISO week (Monday, WAT) and their weekly retention (Story 9-4).
 *
 * @param weekStart   Monday of the signup week
 * @param tenantCount tenants of the cohort
 * @param retention   index k-1 = share (0..1) of the cohort with ≥ 1 active user during week k after the
 *                    signup week; null when that week has not started yet or the cohort is empty
 */
public record RetentionCohort(LocalDate weekStart, int tenantCount, List<Double> retention) {}
