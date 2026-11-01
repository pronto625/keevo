package com.keevo.shared.job.adapter.out.persistence;

import com.keevo.shared.job.AdminJobs.Kind;
import com.keevo.shared.job.AdminJobs.Status;
import com.keevo.shared.job.application.port.AdminJobStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.4 — JDBC adapter of {@link AdminJobStore} on {@code public.admin_jobs}. Every write commits on its own
 * ({@code REQUIRES_NEW}): a job row is visible to the job thread and to the console at once, whatever transaction
 * the caller holds. The partial unique index {@code admin_jobs_one_active_per_kind} makes "one job of a kind at a
 * time" a database check.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcAdminJobStore implements AdminJobStore {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdbcAdminJobStore.class);

    static final String COLUMNS = """
            id, kind, CAST(params AS text) AS params, status, processed, total, CAST(report AS text) AS report, actor,
            created_at, started_at, finished_at, error_message
            """;

    static final String INSERT_SQL = """
            INSERT INTO public.admin_jobs (id, kind, params, status, actor, created_at)
            VALUES (?, ?, CAST(? AS jsonb), 'QUEUED', ?, now())
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public JdbcAdminJobStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean insertQueued(UUID id, Kind kind, String paramsJson, UUID actor) {
        try {
            requiresNew.executeWithoutResult(s -> jdbc.update(INSERT_SQL, id, kind.name(), paramsJson, actor));
            return true;
        } catch (DuplicateKeyException e) {
            return false; // another job of this kind is queued or running
        }
    }

    @Override
    public boolean markRunning(UUID id) {
        Integer count = requiresNew.execute(s -> jdbc.update(
                "UPDATE public.admin_jobs SET status = 'RUNNING', started_at = now() WHERE id = ? AND status = 'QUEUED'",
                id));
        return count != null && count > 0;
    }

    @Override
    public void progress(UUID id, int processed, int total) {
        write("UPDATE public.admin_jobs SET processed = ?, total = ? WHERE id = ? AND status = 'RUNNING'",
                processed, total, id);
    }

    @Override
    public void markSucceeded(UUID id, String reportJson) {
        write("""
                UPDATE public.admin_jobs SET status = 'SUCCEEDED', report = CAST(? AS jsonb), finished_at = now(),
                    processed = COALESCE(total, processed)
                WHERE id = ? AND status = 'RUNNING'
                """, reportJson, id);
    }

    @Override
    public void markFailed(UUID id, String error) {
        write("UPDATE public.admin_jobs SET status = 'FAILED', error_message = ?, finished_at = now() "
                + "WHERE id = ? AND status IN ('QUEUED', 'RUNNING')", truncate(error), id);
    }

    @Override
    public void delete(UUID id) {
        write("DELETE FROM public.admin_jobs WHERE id = ? AND status = 'QUEUED'", id);
    }

    @Override
    public int failUnfinished(String error, Instant createdBefore) {
        Integer count = requiresNew.execute(s -> jdbc.update("""
                UPDATE public.admin_jobs SET status = 'FAILED', error_message = ?, finished_at = now()
                WHERE status IN ('QUEUED', 'RUNNING') AND created_at < ?
                """, truncate(error), createdBefore.atOffset(java.time.ZoneOffset.UTC)));
        return count != null ? count : 0;
    }

    @Override
    public Optional<StoredJob> find(UUID id) {
        List<StoredJob> found = jdbc.query("SELECT " + COLUMNS + " FROM public.admin_jobs WHERE id = ?", MAPPER, id);
        return found.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public List<StoredJob> latest(Kind kind, int limit) {
        List<StoredJob> rows = kind == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM public.admin_jobs ORDER BY created_at DESC, id LIMIT ?",
                        MAPPER, limit)
                : jdbc.query("SELECT " + COLUMNS
                        + " FROM public.admin_jobs WHERE kind = ? ORDER BY created_at DESC, id LIMIT ?", MAPPER,
                        kind.name(), limit);
        return rows.stream().filter(java.util.Objects::nonNull).toList();
    }

    private void write(String sql, Object... args) {
        requiresNew.executeWithoutResult(s -> jdbc.update(sql, args));
    }

    private static String truncate(String error) {
        return error != null && error.length() > 500 ? error.substring(0, 500) : error;
    }

    /** {@code null} for a row whose kind / status this version does not know (e.g. after a rollback). */
    private static final RowMapper<StoredJob> MAPPER = (rs, i) -> {
        Kind kind = parse(Kind.class, rs.getString("kind"));
        Status status = parse(Status.class, rs.getString("status"));
        if (kind == null || status == null) {
            log.warn("Admin job {} skipped: unknown kind or status", rs.getString("id"));
            return null;
        }
        return mapRow(rs, kind, status);
    };

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return value == null ? null : Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StoredJob mapRow(java.sql.ResultSet rs, Kind kind, Status status) throws java.sql.SQLException {
        return new StoredJob(
            rs.getObject("id", UUID.class),
            kind,
            rs.getString("params"),
            status,
            rs.getInt("processed"),
            (Integer) rs.getObject("total"),
            rs.getString("report"),
            rs.getObject("actor", UUID.class),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("started_at", OffsetDateTime.class)),
            instant(rs.getObject("finished_at", OffsetDateTime.class)),
            rs.getString("error_message"));
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }
}
