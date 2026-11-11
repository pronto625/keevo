package com.keevo.whatsappcloud.graph.domain.model;

/**
 * Story 21.5 — a message template as Meta reports it: its id, name, language and review status ({@code PENDING},
 * {@code APPROVED}, {@code REJECTED}, {@code PAUSED}, {@code DISABLED}…), with the rejection reason when there is one
 * ({@code null} for Meta's {@code NONE}).
 *
 * <p>Story 21.6 — with its category, header format ({@code NONE} when none) and body text when Meta's listing gives
 * them (the platform templates the super-admin chooses from); {@code null} otherwise.
 */
public record MetaTemplate(String id, String name, String language, String status, String rejectedReason,
                           String category, String headerFormat, String bodyText) {

    public MetaTemplate(String id, String name, String language, String status, String rejectedReason) {
        this(id, name, language, status, rejectedReason, null, null, null);
    }
}
