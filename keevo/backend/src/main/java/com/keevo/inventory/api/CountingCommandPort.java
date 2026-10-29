package com.keevo.inventory.api;

import java.util.UUID;

/**
 * Inventory counting operations for other modules (Story 12.2b). Same rules, events and
 * transactions as the counting REST endpoints; store-scope and role checks stay with the caller.
 */
public interface CountingCommandPort {

    /**
     * @return the id of the opened (or replayed) session
     * @throws com.keevo.shared.domain.exception.DomainException store/active-session/category rules
     */
    UUID openSession(OpenCountingSessionInput input);

    /** @return the id of the saved count line */
    UUID saveCount(SaveCountInput input);

    /** @return the id of the validated session */
    UUID validate(ValidateCountingInput input);
}
