package com.keevo.admin.notification.domain.port.in;

import com.keevo.admin.notification.domain.model.Announcement;

import java.util.List;

/** Story 9-5 — Keevo announcements sent to the caller's company (merchant app notifications list). */
public interface ListAnnouncementsUseCase {

    /**
     * @param tenantSchema schema of the caller (from the JWT)
     * @param since        ISO-8601 date-time, null / blank = 90 days ago (never older than 90 days)
     */
    List<Announcement> announcements(String tenantSchema, String since);
}
