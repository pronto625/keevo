package com.keevo.migration.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.ReconciliationCheck;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.ReconciliationStatus;
import com.keevo.migration.domain.port.out.ReconciliationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 24.8 — {@link ReconciliationStore} over {@code public.migration_reconciliations} (autocommit, outside the
 * import's transactions). The checks are one JSONB document ({@code {"checks": [...]}}), read whole, never queried.
 */
@Component
class JdbcReconciliationStore implements ReconciliationStore {

    static final String INSERT_SQL = """
            INSERT INTO public.migration_reconciliations (id, batch_id, bref_owner_id, tenant_id, status, gaps,
                                                          explained, report, error_label, started_by, started_at,
                                                          finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """;

    private static final String COLUMNS = "id, batch_id, bref_owner_id, tenant_id, status, gaps, explained, "
            + "report::text AS report, error_label, started_at, finished_at";

    static final String LATEST_FOR_BATCH_SQL = "SELECT " + COLUMNS + " FROM public.migration_reconciliations "
            + "WHERE batch_id = ? ORDER BY finished_at DESC, id LIMIT 1";

    /** The owner's latest batch (any status) — the one the gate judges (review 24.8). */
    static final String LATEST_BATCH_SQL = "SELECT id FROM public.migration_batches WHERE bref_owner_id = ? "
            + "ORDER BY started_at DESC, id LIMIT 1";

    /**
     * The latest run of the owner's latest batch (any status): the same batch the gate judges, so the owner screen
     * never shows « Réconcilié » for an owner the gate blocks (review 24.8).
     */
    static final String LATEST_FOR_OWNER_SQL = "SELECT " + COLUMNS + " FROM public.migration_reconciliations "
            + "WHERE batch_id = (" + LATEST_BATCH_SQL + ") ORDER BY finished_at DESC, id LIMIT 1";

    /**
     * Each owner's latest batch (any status: a later failed re-run may have written rows the reconciliation never
     * saw) and that batch's latest reconciliation; an owner is clear only when both are COMPLETED / RECONCILED.
     */
    static final String BLOCKING_SQL = """
            WITH owners AS (SELECT DISTINCT unnest(?::uuid[]) AS owner_id),
                 latest_batch AS (
                     SELECT DISTINCT ON (b.bref_owner_id) b.bref_owner_id, b.id, b.status
                       FROM public.migration_batches b JOIN owners o ON o.owner_id = b.bref_owner_id
                      ORDER BY b.bref_owner_id, b.started_at DESC, b.id),
                 latest_run AS (
                     SELECT DISTINCT ON (r.batch_id) r.batch_id, r.status
                       FROM public.migration_reconciliations r JOIN latest_batch lb ON lb.id = r.batch_id
                      ORDER BY r.batch_id, r.finished_at DESC, r.id)
            SELECT o.owner_id, lb.status AS batch_status, lr.status AS run_status
              FROM owners o
              LEFT JOIN latest_batch lb ON lb.bref_owner_id = o.owner_id
              LEFT JOIN latest_run lr ON lr.batch_id = lb.id
             WHERE lb.status IS DISTINCT FROM 'COMPLETED' OR lr.status IS DISTINCT FROM 'RECONCILED'
            """;

    private static final TypeReference<List<ReconciliationCheck>> CHECKS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcReconciliationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(ReconciliationReport r, UUID startedBy) {
        String report;
        try {
            report = json.writeValueAsString(Map.of("checks", r.checks()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the reconciliation report", e);
        }
        jdbc.update(INSERT_SQL, r.id(), r.batchId(), r.brefOwnerId(), r.tenantId(), r.status().name(), r.gaps(),
                r.explained(), report, r.errorLabel(), startedBy, Timestamp.from(r.startedAt()),
                Timestamp.from(r.finishedAt()));
    }

    @Override
    public Optional<ReconciliationReport> latestForBatch(UUID batchId) {
        return jdbc.query(LATEST_FOR_BATCH_SQL, (rs, i) -> report(rs), batchId).stream().findFirst();
    }

    @Override
    public Optional<ReconciliationReport> latestForOwner(UUID brefOwnerId) {
        return jdbc.query(LATEST_FOR_OWNER_SQL, (rs, i) -> report(rs), brefOwnerId).stream().findFirst();
    }

    @Override
    public Optional<UUID> latestBatch(UUID brefOwnerId) {
        return jdbc.query(LATEST_BATCH_SQL, (rs, i) -> rs.getObject(1, UUID.class), brefOwnerId).stream()
                .findFirst();
    }

    @Override
    public Map<UUID, String> blockingOwners(Collection<UUID> brefOwnerIds) {
        Map<UUID, String> blocking = new LinkedHashMap<>();
        if (brefOwnerIds.isEmpty()) {
            return blocking;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(BLOCKING_SQL);
            ps.setArray(1, con.createArrayOf("uuid", brefOwnerIds.toArray()));
            return ps;
        }, rs -> {
            blocking.put(rs.getObject("owner_id", UUID.class),
                    reason(rs.getString("batch_status"), rs.getString("run_status")));
        });
        return blocking;
    }

    /** The French reason an owner blocks the activation. */
    static String reason(String batchStatus, String runStatus) {
        if (batchStatus == null) {
            return "Compte Bref non importé";
        }
        if (!"COMPLETED".equals(batchStatus)) {
            return "Dernier import non terminé (bloqué, en échec ou en cours)";
        }
        if (runStatus == null) {
            return "Import non réconcilié";
        }
        return "Écart de réconciliation à corriger";
    }

    private ReconciliationReport report(ResultSet rs) throws SQLException {
        ReconciliationStatus status = ReconciliationStatus.valueOf(rs.getString("status"));
        List<ReconciliationCheck> checks;
        try {
            com.fasterxml.jackson.databind.JsonNode node = json.readTree(rs.getString("report")).path("checks");
            checks = node.isArray() ? json.convertValue(node, CHECKS) : List.of();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read the reconciliation report", e);
        }
        return new ReconciliationReport(rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getObject("bref_owner_id", UUID.class), rs.getObject("tenant_id", UUID.class), status,
                status.label(), rs.getInt("gaps"), rs.getInt("explained"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant(), checks,
                rs.getString("error_label"));
    }
}
