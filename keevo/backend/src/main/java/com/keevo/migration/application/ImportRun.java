package com.keevo.migration.application;

import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 24.2 — the state of one import run, passed along the {@link ImportStep} pipeline: the snapshot, the batch, the
 * actor, the records already known (loaded once, kept up to date by the steps), the imported tenant, the counters and
 * the notes. Not thread-safe (one run = one thread).
 */
public final class ImportRun {

    /** Counter keys of the batch (stable: the dashboard reads them). */
    public static final String TENANTS_CREATED = "tenantsCreated";
    public static final String USERS_CREATED = "usersCreated";
    public static final String MEMBERSHIPS_CREATED = "membershipsCreated";
    public static final String STORES_CREATED = "storesCreated";
    public static final String LOGOS_IMPORTED = "logosImported";
    public static final String EMPLOYEES_CREATED = "employeesCreated";
    public static final String ALREADY_IMPORTED = "alreadyImported";
    public static final String MEMBERS_SKIPPED_DELETED = "membersSkippedDeleted";

    private final BrefSnapshot snapshot;
    private final UUID batchId;
    private final UUID actorId;
    private final Map<MigrationRecord.Key, UUID> records;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<ImportNote.Kind, Integer> notes = new EnumMap<>(ImportNote.Kind.class);
    private UUID tenantId;
    private String tenantSchema;

    public ImportRun(BrefSnapshot snapshot, UUID batchId, UUID actorId, Map<MigrationRecord.Key, UUID> records) {
        this.snapshot = snapshot;
        this.batchId = batchId;
        this.actorId = actorId;
        this.records = records; // the store's mutable map, kept up to date by the steps (no copy)
        for (String key : List.of(TENANTS_CREATED, USERS_CREATED, MEMBERSHIPS_CREATED, STORES_CREATED,
                LOGOS_IMPORTED, EMPLOYEES_CREATED, ALREADY_IMPORTED, MEMBERS_SKIPPED_DELETED)) {
            counts.put(key, 0);
        }
    }

    public BrefSnapshot snapshot() {
        return snapshot;
    }

    public UUID ownerId() {
        return snapshot.owner().id();
    }

    public UUID batchId() {
        return batchId;
    }

    public UUID actorId() {
        return actorId;
    }

    /** The target of a source row in a target table, or {@code null} when not imported yet. */
    public UUID target(String sourceTable, UUID sourceId, String targetTable) {
        return records.get(new MigrationRecord.Key(sourceTable, sourceId, targetTable));
    }

    public boolean imported(String sourceTable, UUID sourceId, String targetTable) {
        return target(sourceTable, sourceId, targetTable) != null;
    }

    /** Remembers records written by a step (after its insert). */
    public void remember(List<MigrationRecord> written) {
        written.forEach(r -> records.put(r.key(), r.targetId()));
    }

    public void count(String key, int delta) {
        counts.merge(key, delta, Integer::sum);
    }

    public void note(ImportNote.Kind kind) {
        notes.merge(kind, 1, Integer::sum);
    }

    public Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    public List<ImportNote> notes() {
        List<ImportNote> list = new ArrayList<>();
        notes.forEach((kind, n) -> list.add(new ImportNote(kind.name(), kind.label(), n)));
        return list;
    }

    public void tenant(UUID id, String schema) {
        this.tenantId = id;
        this.tenantSchema = schema;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String tenantSchema() {
        return tenantSchema;
    }
}
