package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.time.Instant;
import java.util.Objects;

/**
 * Story 19.7a — one plan of the catalogue ({@code public.plans}): offer content, never a constant of the code.
 *
 * @param code        the plan code
 * @param label       French name shown to people (« Gratuit », « Premium »…)
 * @param description French description, {@code null} when none
 * @param sortOrder   display order (ascending)
 * @param visible     offered to new customers
 * @param kind        what the plan is: drives every plan-dependent behaviour
 * @param archivedAt  archived plans keep working for their tenants but are no longer offered; codes are never deleted
 */
public record PlanDefinition(PlanType code, String label, String description, int sortOrder, boolean visible,
                             PlanKind kind, Instant archivedAt) {

    public PlanDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
    }

    /** Whether the plan is archived (kept for its tenants, no longer offered). */
    public boolean archived() {
        return archivedAt != null;
    }
}
