package com.keevo.shared.event.catalog.module;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 16.1 (MOD-FR13) — status transitions of a tenant's paid modules in {@code public.tenant_modules}: written by
 * the nightly licence job (one event per tenant and run), or rows switched off by a plan write (the plan-row sync,
 * {@code DISABLED} only). Published inside the writing transaction, consumed after its commit. Each transition is notified once: a later run finds nothing left to change.
 *
 * @param tenantId    tenant schema name
 * @param transitions the rows that changed status, in module id order (never empty)
 * @param actorId     always {@code null} (system job)
 * @param occurredAt  the run instant
 */
public record ModuleLicencesChangedEvent(
        String tenantId,
        List<Transition> transitions,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {

    public ModuleLicencesChangedEvent {
        Objects.requireNonNull(transitions, "transitions");
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException("A licence change event carries at least one transition");
        }
        transitions = List.copyOf(transitions);
    }

    /** New stored status of a row ({@code tenant_modules.status}). */
    public enum NewStatus { EXPIRED, DISABLED }

    /** Provenance of the row ({@code tenant_modules.origin}): derived from the plan, or set by the super-admin. */
    public enum Origin { PLAN, ADMIN }

    /**
     * @param moduleId    module id (never shown to a user)
     * @param displayName the module's French name (the only name shown)
     * @param origin      row provenance
     * @param status      the status just written
     * @param graceUntil  end of the read-only grace ({@code null}: none)
     */
    public record Transition(String moduleId, String displayName, Origin origin, NewStatus status,
                             Instant graceUntil) {

        public Transition {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(status, "status");
        }
    }
}
