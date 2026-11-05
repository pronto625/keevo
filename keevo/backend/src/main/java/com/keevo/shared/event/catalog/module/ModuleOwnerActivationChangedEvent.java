package com.keevo.shared.event.catalog.module;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 19.13 — the tenant's owner activated or disabled a module of their plan in « Paramètres › Mes modules »
 * ({@code public.tenant_module_activation}). Published inside the writing transaction, only for a real change (the
 * same value is a no-op). Consumed by {@code AuditEventListener} ({@code MODULE_ENABLED_BY_OWNER} /
 * {@code MODULE_DISABLED_BY_OWNER}, labelled with the module's display name). Never a licence change: no
 * {@link ModuleLicencesChangedEvent}, no data purge, no module hook.
 *
 * <p>Pure Java — NO Spring/framework imports.
 *
 * @param tenantId          tenant schema name
 * @param tenantUuid        the tenant's {@code public.tenants.id} (audit entity)
 * @param moduleId          the module id
 * @param moduleDisplayName the module's French display name (audit label)
 * @param activated         the new choice
 * @param actorId           the owner
 * @param occurredAt        when the choice was saved
 */
public record ModuleOwnerActivationChangedEvent(
        String tenantId,
        UUID tenantUuid,
        String moduleId,
        String moduleDisplayName,
        boolean activated,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {

    public ModuleOwnerActivationChangedEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(tenantUuid, "tenantUuid");
        Objects.requireNonNull(moduleId, "moduleId");
    }
}
