package com.keevo.admin.tenant.domain.model;

import java.time.Instant;

/**
 * AdminPlanInfo — current subscription of a tenant read from its schema (source of truth), shown
 * in the « Plan & paiements » section of the detail drawer (Story 9-2).
 *
 * @param planType  FREE | PREMIUM_TRIAL | PREMIUM
 * @param expiresAt null for FREE
 */
public record AdminPlanInfo(String planType, Instant expiresAt) {}
