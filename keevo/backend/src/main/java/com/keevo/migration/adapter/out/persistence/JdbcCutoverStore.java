package com.keevo.migration.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.CutoverReport;
import com.keevo.migration.domain.model.CutoverReport.FigureLine;
import com.keevo.migration.domain.model.CutoverReport.LabelCount;
import com.keevo.migration.domain.model.CutoverReport.StatusCount;
import com.keevo.migration.domain.model.CutoverStatus;
import com.keevo.migration.domain.model.OwnerFigures;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.out.CutoverStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 24.9 — {@link CutoverStore} over {@code public.migration_cutovers} / {@code public.migration_cutover_owners}
 * (V91), autocommit. Lists are keyset pages; the report aggregates are one query each over the cutover's owner lines
 * (figures kept per owner as JSONB, so a rollback that purged the batches keeps the history).
 */
@Component
class JdbcCutoverStore implements CutoverStore {

    static final String INSERT_SQL = """
            INSERT INTO public.migration_cutovers (id, source, status, parallelism, started_by, started_at, heartbeat_at)
            VALUES (?, ?, 'RUNNING', ?, ?, now(), now())
            """;

    private static final String COLUMNS = "c.id, c.source, c.status, c.parallelism, c.runs, c.started_by, "
            + "c.started_at, c.finished_at, c.heartbeat_at, c.activated_by, c.activated_at, c.rolled_back_by, "
            + "c.rolled_back_at, c.error_label, c.accepted_owners::text AS accepted_owners, c.report::text AS report";

    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM public.migration_cutovers c WHERE c.id = ?";
    static final String LIST_SQL = "SELECT " + COLUMNS + " FROM public.migration_cutovers c "
            + "ORDER BY c.started_at DESC, c.id DESC LIMIT ?";
    static final String LIST_AFTER_SQL = "SELECT " + COLUMNS + " FROM public.migration_cutovers c, "
            + "public.migration_cutovers a WHERE a.id = ? AND (c.started_at, c.id) < (a.started_at, a.id) "
            + "ORDER BY c.started_at DESC, c.id DESC LIMIT ?";

    /**
     * One conditional update: a RUNNING / ROLLING_BACK cutover is taken over only when its coordinator stopped beating
     * (restart, crash).
     */
    static final String TRANSITION_SQL = """
            UPDATE public.migration_cutovers
               SET status = ?, heartbeat_at = now(), finished_at = NULL, error_label = NULL, runs = runs + ?
             WHERE id = ? AND status = ANY (?::varchar[])
               AND (status NOT IN ('RUNNING', 'ROLLING_BACK') OR heartbeat_at < now() - make_interval(secs => ?))
            """;

    static final String FINISH_SQL = """
            UPDATE public.migration_cutovers SET status = ?, error_label = ?, report = ?::jsonb, finished_at = now(),
                                                 heartbeat_at = now()
             WHERE id = ? AND status IN ('RUNNING', 'ROLLING_BACK')
            """; // review 24.9: a late coordinator never overwrites a decision (ACTIVATED, a newer state)

    /** Owners appended after the last position, in the listing order; one statement per page. */
    static final String ADD_OWNERS_SQL = """
            INSERT INTO public.migration_cutover_owners (cutover_id, bref_owner_id, position, status)
            SELECT ?, x.id,
                   (SELECT coalesce(max(o.position), 0) FROM public.migration_cutover_owners o WHERE o.cutover_id = ?)
                       + row_number() OVER (ORDER BY x.n), 'PENDING'
              FROM unnest(?::uuid[]) WITH ORDINALITY AS x(id, n)
             WHERE NOT EXISTS (SELECT 1 FROM public.migration_cutover_owners e
                                WHERE e.cutover_id = ? AND e.bref_owner_id = x.id)
            ON CONFLICT DO NOTHING
            """;

    private static final String OWNER_COLUMNS = "o.bref_owner_id, o.position, o.status, o.batch_id, o.tenant_id, "
            + "o.reason, o.import_ms, o.reconcile_ms, o.started_at, o.finished_at";

    static final String TO_PROCESS_SQL = "SELECT " + OWNER_COLUMNS + ", NULL AS company_name "
            + "FROM public.migration_cutover_owners o WHERE o.cutover_id = ? "
            + "AND o.status IN ('PENDING', 'GAP', 'BLOCKED', 'FAILED') AND o.position > ? ORDER BY o.position LIMIT ?";

    /** The company name comes from the tenant row (super-admin screen); absent once removed by a rollback. */
    static final String OWNERS_SQL = "SELECT " + OWNER_COLUMNS + ", t.name AS company_name "
            + "FROM public.migration_cutover_owners o LEFT JOIN public.tenants t ON t.id = o.tenant_id "
            + "WHERE o.cutover_id = ? AND o.position > ? ORDER BY o.position LIMIT ?";
    static final String OWNERS_BY_STATUS_SQL = "SELECT " + OWNER_COLUMNS + ", t.name AS company_name "
            + "FROM public.migration_cutover_owners o LEFT JOIN public.tenants t ON t.id = o.tenant_id "
            + "WHERE o.cutover_id = ? AND o.status = ? AND o.position > ? ORDER BY o.position LIMIT ?";

    /** Story 24.13 — one line by primary key (company name from the tenant row, absent once removed). */
    static final String OWNER_SQL = "SELECT " + OWNER_COLUMNS + ", t.name AS company_name "
            + "FROM public.migration_cutover_owners o LEFT JOIN public.tenants t ON t.id = o.tenant_id "
            + "WHERE o.cutover_id = ? AND o.bref_owner_id = ?";

    /** Story 24.13 — the line of an owner removed alone, back to the start (figures no longer counted). */
    static final String RESET_OWNER_SQL = """
            UPDATE public.migration_cutover_owners
               SET status = 'PENDING', batch_id = NULL, tenant_id = NULL, reason = NULL, import_ms = NULL,
                   reconcile_ms = NULL, figures = '{}'::jsonb, started_at = NULL, finished_at = NULL
             WHERE cutover_id = ? AND bref_owner_id = ?
            """;

    static final String OWNER_DONE_SQL = """
            UPDATE public.migration_cutover_owners
               SET status = ?, batch_id = ?, tenant_id = coalesce(?, tenant_id), reason = ?, import_ms = ?,
                   reconcile_ms = ?, figures = ?::jsonb, finished_at = now()
             WHERE cutover_id = ? AND bref_owner_id = ?
            """;

    static final String TARGETS_SQL = """
            SELECT bref_owner_id, target_table, target_id FROM public.migration_records
             WHERE bref_owner_id = ANY (?::uuid[]) AND target_table = ANY (?::varchar[])
            """;

    static final String STATUS_COUNTS_SQL = "SELECT status, count(*) AS n FROM public.migration_cutover_owners "
            + "WHERE cutover_id = ? GROUP BY status";
    static final String REASONS_SQL = """
            SELECT reason, count(*) AS n FROM public.migration_cutover_owners
             WHERE cutover_id = ? AND reason IS NOT NULL GROUP BY reason ORDER BY n DESC, reason LIMIT 50
            """;
    /** {@code %s} = {@code counts} or {@code sums} (constant, never user input). */
    static final String FIGURES_SQL = """
            SELECT f.key AS label, sum((f.value ->> 'expected')::numeric) AS expected,
                   sum((f.value ->> 'actual')::numeric) AS actual
              FROM public.migration_cutover_owners o, jsonb_each(o.figures -> '%s') f
             WHERE o.cutover_id = ? GROUP BY f.key ORDER BY f.key
            """;
    static final String ANOMALIES_SQL = """
            SELECT a.key AS label, sum((a.value)::text::bigint) AS n
              FROM public.migration_cutover_owners o, jsonb_each(o.figures -> 'anomalies') a
             WHERE o.cutover_id = ? GROUP BY a.key ORDER BY n DESC, a.key
            """;
    static final String OWNER_DURATIONS_SQL = """
            SELECT sum(import_ms) AS imports, sum(reconcile_ms) AS reconciliations,
                   round(avg(coalesce(import_ms, 0) + coalesce(reconcile_ms, 0))) AS avg_ms,
                   percentile_disc(0.95) WITHIN GROUP (ORDER BY coalesce(import_ms, 0) + coalesce(reconcile_ms, 0))
                       AS p95_ms,
                   max(coalesce(import_ms, 0) + coalesce(reconcile_ms, 0)) AS max_ms
              FROM public.migration_cutover_owners WHERE cutover_id = ? AND import_ms IS NOT NULL
            """;

    private static final TypeReference<List<Cutover.AcceptedOwner>> ACCEPTED = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcCutoverStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<UUID> create(SourceKind source, int parallelism, UUID startedBy) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(INSERT_SQL, id, source.name(), parallelism, startedBy);
            return Optional.of(id);
        } catch (DuplicateKeyException open) {
            return Optional.empty(); // uq_migration_cutovers_open
        }
    }

    @Override
    public Optional<Cutover> find(UUID cutoverId) {
        return jdbc.query(FIND_SQL, (rs, i) -> cutover(rs), cutoverId).stream().findFirst()
                .map(c -> withProgress(c, progress(cutoverId)));
    }

    @Override
    public List<Cutover> list(UUID after, int limit) {
        return after == null ? jdbc.query(LIST_SQL, (rs, i) -> cutover(rs), limit)
                : jdbc.query(LIST_AFTER_SQL, (rs, i) -> cutover(rs), after, limit);
    }

    @Override
    public boolean transition(UUID cutoverId, Collection<CutoverStatus> from, CutoverStatus to, Duration staleAfter,
                              boolean newRun) {
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(TRANSITION_SQL);
            ps.setString(1, to.name());
            ps.setInt(2, newRun ? 1 : 0);
            ps.setObject(3, cutoverId);
            ps.setArray(4, con.createArrayOf("varchar", from.stream().map(Enum::name).toArray()));
            ps.setDouble(5, (double) staleAfter.toSeconds());
            return ps;
        }) == 1;
    }

    @Override
    public void heartbeat(UUID cutoverId) {
        jdbc.update("UPDATE public.migration_cutovers SET heartbeat_at = now() WHERE id = ?", cutoverId);
    }

    @Override
    public void finish(UUID cutoverId, CutoverStatus status, String errorLabel, CutoverReport report) {
        jdbc.update(FINISH_SQL, status.name(), errorLabel, write(report), cutoverId);
    }

    @Override
    public void saveReport(UUID cutoverId, CutoverReport report) {
        jdbc.update("UPDATE public.migration_cutovers SET report = ?::jsonb WHERE id = ?", write(report), cutoverId);
    }

    @Override
    public int addOwners(UUID cutoverId, List<UUID> owners) {
        if (owners.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(ADD_OWNERS_SQL);
            ps.setObject(1, cutoverId);
            ps.setObject(2, cutoverId);
            ps.setArray(3, con.createArrayOf("uuid", owners.toArray()));
            ps.setObject(4, cutoverId);
            return ps;
        });
    }

    @Override
    public List<CutoverOwner> ownersToProcess(UUID cutoverId, int afterPosition, int limit) {
        return jdbc.query(TO_PROCESS_SQL, (rs, i) -> owner(rs), cutoverId, afterPosition, limit);
    }

    @Override
    public List<CutoverOwner> owners(UUID cutoverId, CutoverOwnerStatus status, int afterPosition, int limit) {
        return status == null
                ? jdbc.query(OWNERS_SQL, (rs, i) -> owner(rs), cutoverId, afterPosition, limit)
                : jdbc.query(OWNERS_BY_STATUS_SQL, (rs, i) -> owner(rs), cutoverId, status.name(), afterPosition,
                limit);
    }

    @Override
    public List<CutoverOwner> ownerLines(UUID cutoverId, Collection<CutoverOwnerStatus> except) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT " + OWNER_COLUMNS + ", NULL AS company_name "
                    + "FROM public.migration_cutover_owners o WHERE o.cutover_id = ? "
                    + "AND o.status <> ALL (?::varchar[]) ORDER BY o.position");
            ps.setObject(1, cutoverId);
            ps.setArray(2, con.createArrayOf("varchar", except.stream().map(Enum::name).toArray()));
            return ps;
        }, (rs, i) -> owner(rs));
    }

    @Override
    public void ownerStarted(UUID cutoverId, UUID ownerId, Instant at) {
        jdbc.update("UPDATE public.migration_cutover_owners SET started_at = ? WHERE cutover_id = ? "
                + "AND bref_owner_id = ?", Timestamp.from(at), cutoverId, ownerId);
    }

    @Override
    public void ownerDone(UUID cutoverId, UUID ownerId, CutoverOwnerStatus status, UUID batchId, UUID tenantId,
                          String reason, Integer importMs, Integer reconcileMs, OwnerFigures figures) {
        jdbc.update(OWNER_DONE_SQL, status.name(), batchId, tenantId, reason, importMs, reconcileMs,
                write(figures == null ? OwnerFigures.NONE : figures), cutoverId, ownerId);
    }

    @Override
    public void ownersStatus(UUID cutoverId, Collection<UUID> ownerIds, CutoverOwnerStatus status, String reason) {
        if (ownerIds.isEmpty()) {
            return;
        }
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("UPDATE public.migration_cutover_owners SET status = ?, "
                    + "reason = ? WHERE cutover_id = ? AND bref_owner_id = ANY (?::uuid[])");
            ps.setString(1, status.name());
            ps.setString(2, reason);
            ps.setObject(3, cutoverId);
            ps.setArray(4, con.createArrayOf("uuid", ownerIds.toArray()));
            return ps;
        });
    }

    @Override
    public Optional<CutoverOwner> owner(UUID cutoverId, UUID ownerId) {
        return jdbc.query(OWNER_SQL, (rs, i) -> owner(rs), cutoverId, ownerId).stream().findFirst();
    }

    @Override
    public void resetOwner(UUID cutoverId, UUID ownerId) {
        jdbc.update(RESET_OWNER_SQL, cutoverId, ownerId);
    }

    @Override
    public void activated(UUID cutoverId, UUID actor, List<Cutover.AcceptedOwner> accepted) {
        // the status moved to ACTIVATED through transition(); the first « go » keeps its actor and time
        jdbc.update("UPDATE public.migration_cutovers SET activated_by = coalesce(activated_by, ?), "
                + "activated_at = coalesce(activated_at, now()), accepted_owners = accepted_owners || ?::jsonb, "
                + "finished_at = now(), heartbeat_at = now() WHERE id = ? AND status = 'ACTIVATED'", actor,
                write(accepted), cutoverId);
    }

    @Override
    public void rolledBack(UUID cutoverId, UUID actor) {
        jdbc.update("UPDATE public.migration_cutovers SET rolled_back_by = ?, rolled_back_at = now() WHERE id = ?",
                actor, cutoverId);
    }

    @Override
    public Map<UUID, Map<String, List<UUID>>> targets(Collection<UUID> ownerIds, Collection<String> targetTables) {
        Map<UUID, Map<String, List<UUID>>> out = new HashMap<>();
        if (ownerIds.isEmpty() || targetTables.isEmpty()) {
            return out;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(TARGETS_SQL);
            ps.setArray(1, con.createArrayOf("uuid", ownerIds.toArray()));
            ps.setArray(2, con.createArrayOf("varchar", targetTables.toArray()));
            return ps;
        }, rs -> {
            out.computeIfAbsent(rs.getObject("bref_owner_id", UUID.class), k -> new HashMap<>())
                    .computeIfAbsent(rs.getString("target_table"), k -> new ArrayList<>())
                    .add(rs.getObject("target_id", UUID.class));
        });
        return out;
    }

    @Override
    public void purgeOwners(Collection<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return;
        }
        Object[] ids = ownerIds.toArray();
        // records reference their batch (no cascade): records first; the reconciliations cascade with the batches
        for (String sql : List.of("DELETE FROM public.migration_records WHERE bref_owner_id = ANY (?::uuid[])",
                "DELETE FROM public.migration_batches WHERE bref_owner_id = ANY (?::uuid[])")) {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                ps.setArray(1, con.createArrayOf("uuid", ids));
                return ps;
            });
        }
    }

    @Override
    public CutoverReport aggregate(UUID cutoverId, CutoverReport.Durations stages, CutoverReport.IndexProbe probe,
                                   int acceptedOwners) {
        List<StatusCount> owners = progress(cutoverId);
        int total = (int) owners.stream().mapToLong(StatusCount::count).sum();
        List<LabelCount> reasons = jdbc.query(REASONS_SQL, (rs, i) -> new LabelCount(rs.getString("reason"),
                rs.getLong("n")), cutoverId);
        List<FigureLine> tables = figures("counts", cutoverId);
        List<FigureLine> sums = figures("sums", cutoverId);
        List<LabelCount> anomalies = jdbc.query(ANOMALIES_SQL, (rs, i) -> new LabelCount(rs.getString("label"),
                rs.getLong("n")), cutoverId);
        CutoverReport.Durations owner = jdbc.query(OWNER_DURATIONS_SQL, rs -> {
            rs.next();
            return new CutoverReport.Durations(null, null, longOrNull(rs, "imports"),
                    longOrNull(rs, "reconciliations"), longOrNull(rs, "avg_ms"), longOrNull(rs, "p95_ms"),
                    longOrNull(rs, "max_ms"), null, null, null);
        }, cutoverId);
        CutoverReport.Durations s = stages == null ? CutoverReport.Durations.NONE : stages;
        CutoverReport.Durations durations = new CutoverReport.Durations(s.listingMs(), s.runWallMs(),
                owner.importsMs(), owner.reconciliationsMs(), owner.ownerAvgMs(), owner.ownerP95Ms(),
                owner.ownerMaxMs(), s.activationMs(), s.rollbackMs(), s.totalMs());
        return new CutoverReport(Instant.now(), total, owners, reasons, tables, sums, anomalies, durations, probe,
                acceptedOwners);
    }

    private List<FigureLine> figures(String section, UUID cutoverId) {
        return jdbc.query(FIGURES_SQL.formatted(section), (rs, i) -> {
            BigDecimal expected = rs.getBigDecimal("expected");
            BigDecimal actual = rs.getBigDecimal("actual");
            return new FigureLine(rs.getString("label"), expected, actual,
                    expected == null || actual == null ? null : actual.subtract(expected));
        }, cutoverId);
    }

    @Override
    public List<StatusCount> progress(UUID cutoverId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(STATUS_COUNTS_SQL, rs -> {
            counts.put(rs.getString("status"), rs.getLong("n"));
        }, cutoverId);
        List<StatusCount> out = new ArrayList<>();
        for (CutoverOwnerStatus s : CutoverOwnerStatus.values()) {
            long n = counts.getOrDefault(s.name(), 0L);
            if (n > 0) {
                out.add(new StatusCount(s.name(), s.label(), n));
            }
        }
        return out;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.longValue();
    }

    private Cutover cutover(ResultSet rs) throws SQLException {
        return new Cutover(rs.getObject("id", UUID.class), SourceKind.valueOf(rs.getString("source")),
                CutoverStatus.valueOf(rs.getString("status")), rs.getInt("parallelism"), rs.getInt("runs"),
                rs.getObject("started_by", UUID.class), instant(rs, "started_at"), instant(rs, "finished_at"),
                instant(rs, "heartbeat_at"), rs.getObject("activated_by", UUID.class), instant(rs, "activated_at"),
                rs.getObject("rolled_back_by", UUID.class), instant(rs, "rolled_back_at"),
                rs.getString("error_label"), List.of(), read(rs.getString("accepted_owners"), ACCEPTED),
                readReport(rs.getString("report")));
    }

    private static Cutover withProgress(Cutover c, List<StatusCount> progress) {
        return new Cutover(c.id(), c.source(), c.status(), c.parallelism(), c.runs(), c.startedBy(), c.startedAt(),
                c.finishedAt(), c.heartbeatAt(), c.activatedBy(), c.activatedAt(), c.rolledBackBy(),
                c.rolledBackAt(), c.errorLabel(), progress, c.acceptedOwners(), c.report());
    }

    private static CutoverOwner owner(ResultSet rs) throws SQLException {
        int position = rs.getInt("position");
        return new CutoverOwner(rs.getObject("bref_owner_id", UUID.class), position, CutoverOwner.label(position),
                rs.getString("company_name"), CutoverOwnerStatus.valueOf(rs.getString("status")),
                rs.getObject("batch_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("reason"),
                (Integer) rs.getObject("import_ms"), (Integer) rs.getObject("reconcile_ms"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private CutoverReport readReport(String value) {
        if (value == null || value.isBlank() || "{}".equals(value.strip())) {
            return CutoverReport.EMPTY;
        }
        try {
            return json.readValue(value, CutoverReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read the cutover report", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read cutover data", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize cutover data", e);
        }
    }
}
