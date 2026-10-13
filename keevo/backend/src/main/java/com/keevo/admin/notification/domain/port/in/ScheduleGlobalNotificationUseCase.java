package com.keevo.admin.notification.domain.port.in;

import java.util.UUID;

/** Story 9-5 — schedules a global push (now or later). */
public interface ScheduleGlobalNotificationUseCase {

    /** Stored as SCHEDULED; returns its id and the devices currently targeted. */
    Scheduled schedule(ScheduleGlobalNotificationCommand command);

    record Scheduled(UUID id, long recipientEstimate) {}
}
