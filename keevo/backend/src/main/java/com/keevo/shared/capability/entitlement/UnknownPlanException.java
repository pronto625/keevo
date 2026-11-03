package com.keevo.shared.capability.entitlement;

/**
 * Story 19.7a — a plan code that is not in the catalogue. An {@link IllegalArgumentException}, like the former
 * {@code PlanType.valueOf} failure, so the callers keep mapping it to their existing 404 / 422 domain errors. The
 * message never echoes the raw value.
 */
public class UnknownPlanException extends IllegalArgumentException {

    public UnknownPlanException() {
        super("Unknown plan code");
    }
}
