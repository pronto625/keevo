package com.keevo.admin.subscription.adapter.in.rest.dto;

import java.time.Instant;

/** PlanStateDto — plan after an admin plan action (Story 9-2). {@code expiresAt} null for FREE. */
public record PlanStateDto(String planType, Instant expiresAt) {}
