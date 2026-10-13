package com.keevo.admin.notification.domain.port.in;

import java.util.UUID;

/** Story 9-5 — cancels a global push still SCHEDULED (404 unknown, 409 INVALID_STATE otherwise). */
public interface CancelGlobalNotificationUseCase {
    void cancel(UUID id, UUID actorId);
}
