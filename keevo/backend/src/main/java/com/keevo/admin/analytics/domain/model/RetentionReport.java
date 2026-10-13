package com.keevo.admin.analytics.domain.model;

import java.util.List;

/** Weekly tenant cohorts, most recent first (Story 9-4). {@code weeks} = number of retention columns. */
public record RetentionReport(int weeks, List<RetentionCohort> cohorts) {}
