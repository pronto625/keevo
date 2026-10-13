package com.keevo.admin.notification.adapter.in.rest.dto;

import com.keevo.admin.notification.domain.model.Announcement;

import java.util.UUID;

/** A Keevo announcement for the merchant app ({@code sentAt} ISO-8601 UTC). */
public record AnnouncementDto(UUID id, String title, String body, String sentAt) {
    public static AnnouncementDto from(Announcement a) {
        return new AnnouncementDto(a.id(), a.title(), a.body(), a.sentAt().toString());
    }
}
