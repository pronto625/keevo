package com.keevo.admin.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/** GlobalPushSentEvent — a global push of the super admin was sent (Story 9-5, FR82). */
public record GlobalPushSentEvent(UUID actorId, GlobalNotificationTarget target, int recipientCount,
                                  Instant sentAt, String title) {}
