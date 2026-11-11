package com.keevo.whatsappcloud.inbox.domain.model;

import java.util.UUID;

/**
 * Story 21.6 — a delivery status that changed on a campaign message: the campaign counters move by the difference
 * between the two statuses ({@link #sentDelta()} …), set-based per campaign.
 */
public record StatusChange(UUID campaignId, String oldStatus, String newStatus) {

    public int sentDelta() {
        return sent(newStatus) - sent(oldStatus);
    }

    public int deliveredDelta() {
        return delivered(newStatus) - delivered(oldStatus);
    }

    public int readDelta() {
        return read(newStatus) - read(oldStatus);
    }

    public int failedDelta() {
        return failed(newStatus) - failed(oldStatus);
    }

    /** Bref's counters: sent = SENT | DELIVERED | READ, delivered = DELIVERED | READ, read = READ, failed = FAILED. */
    static int sent(String s) {
        return "SENT".equals(s) || "DELIVERED".equals(s) || "READ".equals(s) ? 1 : 0;
    }

    static int delivered(String s) {
        return "DELIVERED".equals(s) || "READ".equals(s) ? 1 : 0;
    }

    static int read(String s) {
        return "READ".equals(s) ? 1 : 0;
    }

    static int failed(String s) {
        return "FAILED".equals(s) ? 1 : 0;
    }
}
