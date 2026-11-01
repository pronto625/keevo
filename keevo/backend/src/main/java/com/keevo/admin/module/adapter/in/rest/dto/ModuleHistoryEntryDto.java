package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

import java.time.Instant;

/**
 * One entry of a tenant's module history (Story 16.4): when, what (French label), on which module or pack, a French
 * summary, and who (name and phone). No identifier.
 */
public record ModuleHistoryEntryDto(Instant occurredAt, String action, String subject, String detail,
                                    String actorName, String actorPhone) {

    public static ModuleHistoryEntryDto from(ModuleLifecycle.HistoryEntry entry) {
        return new ModuleHistoryEntryDto(entry.occurredAt(), entry.action(), entry.subject(), entry.detail(),
                entry.actorName(), entry.actorPhone());
    }
}
