package com.keevo.shared.event.catalog;

/**
 * TenantScopedEvent — the tenant key read by the module-listener tenant binding (Story 12.4).
 *
 * <p>An {@code @ApplicationModuleListener} runs asynchronously, possibly after a restart (registry
 * replay), so it cannot rely on the publisher's {@code TenantContext}: the binding interceptor sets the
 * context from {@link #tenantSchema()} before the listener (and its transaction) starts, and restores the
 * previous one afterwards.
 *
 * <p>Every {@link DomainEvent} is tenant scoped through its {@code tenantId()} (the tenant schema). Events
 * whose payload carries the tenant as a {@code UUID tenantId} component (plan changes) implement this
 * interface directly and return their {@code schemaName} — no payload field is added or renamed, and the
 * accessor is not a bean property, so the serialized form is unchanged.
 */
public interface TenantScopedEvent {

    /** Tenant schema name (e.g. {@code kv_ab12cd34}) the listener must run in; {@code null} = no tenant. */
    String tenantSchema();
}
