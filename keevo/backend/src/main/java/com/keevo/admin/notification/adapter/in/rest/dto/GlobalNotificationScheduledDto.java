package com.keevo.admin.notification.adapter.in.rest.dto;

import java.util.UUID;

/** Answer of a scheduled global push: its id (status polling / cancel, never displayed) and device estimate. */
public record GlobalNotificationScheduledDto(UUID id, long recipientEstimate) {}
