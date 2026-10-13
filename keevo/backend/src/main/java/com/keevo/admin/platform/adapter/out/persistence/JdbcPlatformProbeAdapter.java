package com.keevo.admin.platform.adapter.out.persistence;

import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JdbcPlatformProbeAdapter — live database probes of the health view (Story 9-5).
 *
 * <p>The database check runs on its own {@link JdbcTemplate} with a 5 s query timeout so a hung
 * database answers ❌ instead of blocking the page. Sync refusals loop over the live companies'
 * schemas (deliberately no transaction: each count autocommits, one broken schema cannot abort the
 * next ones); a company's own SQL error skips it, connection / resource failures are rethrown.
 */
@Component
public class JdbcPlatformProbeAdapter implements PlatformProbePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlatformProbeAdapter.class);

    static final String DB_CHECK_SQL = "SELECT 1 FROM public.tenants LIMIT 1";
    static final int DB_CHECK_TIMEOUT_SECONDS = 5;

    static final String LIVE_SCHEMAS_SQL =
            "SELECT schema_name FROM public.tenants WHERE status <> 'DELETED' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";

    static final String REFUSALS_SQL = "SELECT COUNT(*) FROM \"%s\".sync_error_log WHERE created_at >= ?";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate checkJdbc;

    public JdbcPlatformProbeAdapter(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.checkJdbc = new JdbcTemplate(dataSource);
        this.checkJdbc.setQueryTimeout(DB_CHECK_TIMEOUT_SECONDS);
    }

    @Override
    public boolean databaseOk() {
        try {
            checkJdbc.queryForList(DB_CHECK_SQL);
            return true;
        } catch (RuntimeException e) {
            log.warn("Health: database check failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public long syncRefusalsSince(Instant since) {
        Timestamp from = Timestamp.from(since);
        long total = 0;
        List<String> schemas = jdbc.queryForList(LIVE_SCHEMAS_SQL, String.class);
        for (String schema : schemas) {
            if (!TenantSchema.isValid(schema)) continue;
            try {
                Long n = jdbc.queryForObject(String.format(REFUSALS_SQL, schema), Long.class, from);
                total += n == null ? 0 : n;
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e; // outage / pool exhaustion: never answer a falsely low count
            } catch (DataAccessException e) {
                log.warn("Health: sync refusals of one company unreadable ({})", e.getClass().getSimpleName());
            }
        }
        return total;
    }
}
