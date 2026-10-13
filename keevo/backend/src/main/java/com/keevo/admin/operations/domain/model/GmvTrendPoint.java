package com.keevo.admin.operations.domain.model;

import java.time.LocalDate;

/** One day (Africa/Douala calendar day) of the platform GMV series (Story 9.7). */
public record GmvTrendPoint(LocalDate date, long gmv, long orderCount) {}
