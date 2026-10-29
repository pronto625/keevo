package com.keevo.inventory.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to inventory counting sessions for other modules (Story 12.2b). */
public interface CountingQueryPort {

    /** Store of a session, empty when the session does not exist. */
    Optional<UUID> findSessionStoreId(String tenantSchema, UUID sessionId);

    /** True when the session exists and is already VALIDATED. */
    boolean isSessionValidated(String tenantSchema, UUID sessionId);
}
