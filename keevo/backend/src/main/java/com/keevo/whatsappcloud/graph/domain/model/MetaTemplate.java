package com.keevo.whatsappcloud.graph.domain.model;

/**
 * Story 21.5 — a message template as Meta reports it: its id, name, language and review status ({@code PENDING},
 * {@code APPROVED}, {@code REJECTED}, {@code PAUSED}, {@code DISABLED}…), with the rejection reason when there is one
 * ({@code null} for Meta's {@code NONE}).
 */
public record MetaTemplate(String id, String name, String language, String status, String rejectedReason) {
}
