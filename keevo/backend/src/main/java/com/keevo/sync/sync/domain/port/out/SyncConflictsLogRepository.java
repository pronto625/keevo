package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.SyncConflictsLogEntry;

import java.util.List;

/**
 * SyncConflictsLogRepository — Port for conflict event persistence.
 */
public interface SyncConflictsLogRepository {

    void save(SyncConflictsLogEntry entry);

    List<SyncConflictsLogEntry> findAll(int limit, int offset);

    List<SyncConflictsLogEntry> findByEntityId(String entityId);
}
