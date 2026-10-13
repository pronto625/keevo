package com.keevo.admin.notification.domain.port.out;

import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.model.PushTargetTenant;
import com.keevo.admin.notification.domain.model.PushTenantRef;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Audience of a global push (Story 9-5): companies neither DELETED nor SUSPENDED, never the
 * {@code KV-ADMIN} system company, filtered by their current plan ({@code public.tenants.plan_type}).
 */
public interface PushAudiencePort {

    /** Companies reached by the target ({@code tenantId} only read for TENANT). */
    List<PushTargetTenant> targetTenants(GlobalNotificationTarget target, UUID tenantId);

    /** The company if it can receive a global push (exists, active or deletion pending, not KV-ADMIN). */
    Optional<PushTargetTenant> reachableTenant(UUID tenantId);

    /** Reachable companies whose name contains {@code search} (case-insensitive), by name, at most {@code limit}. */
    List<PushTenantRef> searchReachable(String search, int limit);

    /** The company of a tenant schema (any status but DELETED), for the app announcements. */
    Optional<PushTenantRef> tenantOfSchema(String schemaName);

    /**
     * Every device token of the company's users (owners and employees). An SQL / schema error of
     * that company yields an empty list (logged); connection / resource failures are rethrown.
     */
    List<String> deviceTokens(PushTargetTenant tenant);

    /** Distinct device tokens of the companies (a token in several schemas counts once), same skip / rethrow rules. */
    long countDeviceTokens(List<PushTargetTenant> tenants);
}
