package com.keevo.admin.platform.domain.port.in;

import com.keevo.admin.platform.domain.model.SystemHealth;

/** GetSystemHealthUseCase — « Santé système » page and incident banner (Story 9-5, FR83). */
public interface GetSystemHealthUseCase {

    /** Freshly computed health (live database check, live connection gauge). */
    SystemHealth health();

    /**
     * Health computed at most a few seconds ago — the incident banner polls every 60 s from every
     * open admin page, and the alerts feed reads it too: one computation serves them all.
     */
    SystemHealth recentHealth();
}
