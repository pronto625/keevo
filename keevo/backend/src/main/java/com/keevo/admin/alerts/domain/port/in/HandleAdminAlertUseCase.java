package com.keevo.admin.alerts.domain.port.in;

import java.util.UUID;

/** HandleAdminAlertUseCase — « Marquer comme traité » (Story 9-5); idempotent, unknown key accepted. */
public interface HandleAdminAlertUseCase {

    void markHandled(String alertKey, UUID actorId);
}
