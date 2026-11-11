package com.keevo.migration.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 24.2 — {@link MigrationRecordStore} over {@code public.migration_batches} / {@code public.migration_records}.
 * Batches are written outside the steps' transactions (autocommit), records inside them. Counts, cases and notes are
 * JSONB documents (read whole, never queried).
 */
@Component
class JdbcMigrationRecordStore implements MigrationRecordStore {

    static final String START_SQL = """
            INSERT INTO public.migration_batches (id, bref_owner_id, source, status, started_by, started_at)
            VALUES (?, ?, ?, 'RUNNING', ?, now())
            """;

    /** Closes an interrupted run (older than the stale delay) so the owner can be imported again. */
    static final String CLOSE_STALE_SQL = """
            UPDATE public.migration_batches
               SET status = 'FAILED', finished_at = now(), error_label = 'Import interrompu (repris par un nouvel import)'
             WHERE bref_owner_id = ? AND status = 'RUNNING' AND started_at < now() - make_interval(secs => ?)
            """;

    static final String FINISH_SQL = """
            UPDATE public.migration_batches
               SET status = ?, tenant_id = ?, finished_at = now(), counts = ?::jsonb, cases = ?::jsonb,
                   notes = ?::jsonb, error_label = ?
             WHERE id = ?
            """;

    static final String FIND_BATCH_SQL = """
            SELECT id, bref_owner_id, source, status, tenant_id, started_by, started_at, finished_at,
                   counts::text AS counts, cases::text AS cases, notes::text AS notes, error_label
              FROM public.migration_batches WHERE id = ?
            """;

    static final String FIND_TARGETS_SQL = """
            SELECT source_table, source_id, target_table, target_id
              FROM public.migration_records WHERE bref_owner_id = ? AND target_table = ANY (?::varchar[])
            """;

    static final String INSERT_RECORD_SQL = """
            INSERT INTO public.migration_records (batch_id, bref_owner_id, target_table, target_id, source_table,
                                                  source_id, checksum, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_table, source_id, target_table) DO NOTHING
            """;

    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() { };
    private static final TypeReference<List<BlockedCase>> CASES = new TypeReference<>() { };
    private static final TypeReference<List<ImportNote>> NOTES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcMigrationRecordStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<UUID> startBatch(UUID brefOwnerId, SourceKind source, UUID startedBy, Duration staleAfter) {
        jdbc.update(CLOSE_STALE_SQL, brefOwnerId, (double) staleAfter.toSeconds()); // database clock only
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(START_SQL, id, brefOwnerId, source.name(), startedBy);
            return Optional.of(id);
        } catch (DuplicateKeyException running) {
            return Optional.empty(); // uq_migration_batches_running: a recent run of this owner is in progress
        }
    }

    @Override
    public void finishBatch(UUID batchId, BatchStatus status, UUID tenantId, Map<String, Integer> counts,
                            List<BlockedCase> cases, List<ImportNote> notes, String errorLabel) {
        jdbc.update(FINISH_SQL, status.name(), tenantId, write(counts), write(cases), write(notes), errorLabel,
                batchId);
    }

    @Override
    public Optional<MigrationBatch> findBatch(UUID batchId) {
        return jdbc.query(FIND_BATCH_SQL, (rs, i) -> batch(rs), batchId).stream().findFirst();
    }

    @Override
    public Map<MigrationRecord.Key, UUID> findTargets(UUID brefOwnerId, Collection<String> targetTables) {
        Map<MigrationRecord.Key, UUID> targets = new HashMap<>();
        if (targetTables.isEmpty()) {
            return targets;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(FIND_TARGETS_SQL);
            ps.setObject(1, brefOwnerId);
            ps.setArray(2, con.createArrayOf("varchar", targetTables.toArray()));
            return ps;
        }, rs -> {
            targets.put(new MigrationRecord.Key(rs.getString("source_table"), rs.getObject("source_id", UUID.class),
                    rs.getString("target_table")), rs.getObject("target_id", UUID.class));
        });
        return targets;
    }

    @Override
    public void insert(UUID batchId, UUID brefOwnerId, Collection<MigrationRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<MigrationRecord> list = List.copyOf(records);
        jdbc.batchUpdate(INSERT_RECORD_SQL, list, list.size(), (ps, r) -> {
            ps.setObject(1, batchId);
            ps.setObject(2, brefOwnerId);
            ps.setString(3, r.targetTable());
            ps.setObject(4, r.targetId());
            ps.setString(5, r.sourceTable());
            ps.setObject(6, r.sourceId());
            ps.setString(7, r.checksum());
            ps.setString(8, r.note());
        });
    }

    private MigrationBatch batch(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new MigrationBatch(rs.getObject("id", UUID.class), rs.getObject("bref_owner_id", UUID.class),
                SourceKind.valueOf(rs.getString("source")), BatchStatus.valueOf(rs.getString("status")),
                rs.getObject("tenant_id", UUID.class), rs.getObject("started_by", UUID.class),
                rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                read(rs.getString("counts"), COUNTS), read(rs.getString("cases"), CASES),
                read(rs.getString("notes"), NOTES), rs.getString("error_label"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize migration batch data", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read migration batch data", e);
        }
    }
}
