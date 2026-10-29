package com.keevo.shared.event.catalog;

import java.time.Instant;
import java.util.UUID;

/**
 * DomainEvent — common envelope of the inter-module events of the shared catalogue (Story 12.3).
 *
 * <p>Every event consumed outside its producer module lives under {@code com.keevo.shared.event.catalog}
 * (the future {@code core.event.catalog}, spec-socle §7.4) so a consumer depends on the catalogue only,
 * never on the producer module. Producers publish them through the standard
 * {@code ApplicationEventPublisher}; publication and listener semantics are unchanged.
 *
 * <p>The envelope only names data the events already carry — no payload field is added or renamed, so
 * audit details and notifications stay identical. {@code eventId}/{@code type} are not part of it yet:
 * the Spring Modulith publication registry (Story 12.4) identifies publications itself.
 *
 * <p>Events whose existing payload carries the tenant as a {@code UUID} component named {@code tenantId}
 * (account deletion, user registration, plan changes) or no tenant at all (cross-tenant password reset)
 * cannot implement this interface without renaming a payload field; they live in the catalogue without
 * the envelope until Story 12.4 settles the registry's tenant key.
 *
 * <p>Story 12.4: the envelope is a {@link TenantScopedEvent} — the module-listener tenant binding reads
 * {@link #tenantSchema()} (= {@link #tenantId()}). The plan-change events, which cannot carry this envelope,
 * implement {@link TenantScopedEvent} with their schema name.
 */
public interface DomainEvent extends TenantScopedEvent {

    /** Tenant schema name (e.g. {@code kv_ab12cd34}) the event belongs to. */
    String tenantId();

    /** When the business fact happened. */
    Instant occurredAt();

    /** User who triggered the fact; {@code null} for system events (schedulers, automatic cascades). */
    UUID actorId();

    /** Story 12.4 — the tenant schema used to bind {@code TenantContext} around module listeners. */
    @Override
    default String tenantSchema() {
        return tenantId();
    }
}
