package com.keevo.commerce.api;

/**
 * Day-closure writes for other modules (Story 12.2c). Runs the closing use case unchanged (summary,
 * window, {@code DayClosedEvent}); the use case is REQUIRED, so it joins the caller's transaction
 * (opening one only when there is none).
 */
public interface DayClosureCommandPort {

    /**
     * Closes the store's day.
     *
     * @return the recorded closure
     * @throws com.keevo.shared.domain.exception.DomainException DAY_ALREADY_CLOSED
     */
    DayClosureView closeDay(String tenantSchema, CloseDayInput input);
}
