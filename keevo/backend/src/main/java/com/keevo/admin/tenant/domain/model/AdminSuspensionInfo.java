package com.keevo.admin.tenant.domain.model;

import java.time.Instant;

/**
 * AdminSuspensionInfo — lasting trace of the current suspension of a tenant ({@code public.tenants}
 * suspended_at / suspended_reason, Story 9-2), shown in the detail drawer. Null when not suspended.
 *
 * @param suspendedAt when the super admin suspended the tenant
 * @param reason      reason entered by the super admin
 */
public record AdminSuspensionInfo(Instant suspendedAt, String reason) {}
