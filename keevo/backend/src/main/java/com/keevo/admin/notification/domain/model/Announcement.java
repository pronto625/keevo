package com.keevo.admin.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/** A sent global push as the merchant app lists it (Story 9-5). */
public record Announcement(UUID id, String title, String body, Instant sentAt) {}
