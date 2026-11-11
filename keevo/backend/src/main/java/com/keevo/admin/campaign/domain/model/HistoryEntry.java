package com.keevo.admin.campaign.domain.model;

import java.time.Instant;

/** Story 21.6 — one line of a platform campaign's audit: the French action, who (a name, never an id), detail, when. */
public record HistoryEntry(String action, String actionLabel, String actorName, String detail, Instant occurredAt) {
}
