package com.keevo.identity.onboarding.domain.model;

/**
 * PreferenceUpdatedEvent — Domain event published after report preferences are updated.
 * Carries the new EOD report time so downstream listeners (e.g. scheduler) can adapt.
 */
public record PreferenceUpdatedEvent(String tenantId, String newEodReportTime) {}
