package com.keevo.admin.platform.adapter.out.persistence;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * database answers ❌ instead of blocking the page. Sync refusals are counted over the live companies'
 * schemas (Story 19.11c, FUS-NFR11: the live companies with their table presence in one query, then one
 * {@code UNION ALL} per 100 companies — never a statement per company); a company's own SQL error skips it,
 * a company without the table counts 0, connection / resource failures are rethrown.
 */
@Component
public class JdbcPlatformProbeAdapter implements PlatformProbePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlatformProbeAdapter.class);

    static final String DB_CHECK_SQL = "SELECT 1 FROM public.tenants LIMIT 1";
    static final int DB_CHECK_TIMEOUT_SECONDS = 5;

    /** One branch of the union (the schema); parameter: the instant. */
    static final String REFUSALS_SQL = "SELECT COUNT(*) AS n FROM \"%s\".sync_error_log WHERE created_at >= ?";

    private final CrossTenantSql crossTenant;
    private final JdbcTemplate checkJdbc;

    /** With cross-tenant statements of the default chunk size (tests). */
    public JdbcPlatformProbeAdapter(JdbcTemplate jdbc, DataSource dataSource) {
        this(new CrossTenantSql(jdbc, CrossTenantSql.DEFAULT_CHUNK_SIZE), dataSource);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcPlatformProbeAdapter(CrossTenantSql crossTenant, DataSource dataSource) {
        this.crossTenant = crossTenant;
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
        // Live companies (not DELETED, never KV-ADMIN) holding the table: one query; then one union per 100.
        List<TenantBranch> tenants = crossTenant.liveTenants().stream()
                .filter(t -> t.has(TenantTable.SYNC_ERRORS)).toList();
        long total = 0;
        for (Long n : crossTenant.query(tenants, (tenant, params) -> {
                    params.add(from);
                    return String.format(REFUSALS_SQL, TenantSchema.validate(tenant.schemaName()));
                },
                union -> "SELECT COALESCE(SUM(n), 0) FROM (" + union + ") u", (rs, i) -> rs.getLong(1))) {
            total += n;
        }
        return total;
    }
}
