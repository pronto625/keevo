package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ExistingSchemaBaseline;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SmartDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Programmatic Flyway per (tenant schema, module) — Story 13.4, spec-socle §2.3. One {@link Flyway} instance per
 * call: {@code schemas}/{@code defaultSchema} = the tenant schema, history table {@code flyway_history_<id>}
 * ({@code -} → {@code _}), locations {@code classpath:db/modules/<id>}, {@code baselineOnMigrate},
 * {@code createSchemas(false)}, {@code validateMigrationNaming(true)} (a misnamed script beside valid ones fails
 * instead of being silently ignored), Flyway default placeholders only.
 *
 * <p><b>Dedicated connections.</b> Flyway switches {@code search_path} to the tenant schema on the connections it
 * uses; on a pooled connection a failed restore would hand a tenant-scoped connection back to the pool. Flyway
 * therefore never sees the application pool: it gets a non-pooled {@link DriverManagerDataSource} built from the
 * pool's URL and credentials (every connection is physical and closed after use). A DataSource that is already
 * driver-based (non-pooled) is used as is; any other type is refused.
 *
 * <p><b>{@code baselineVersion("0")}</b> (deviation from the §2.3 snippet, same choice as the public Flyway): a
 * tenant schema is never empty, so with Flyway's default baseline version {@code 1} the first run would record a
 * baseline at 1 and silently skip the module's {@code V1}. The baseline row is ignored for the version.
 *
 * <p><b>Existing-schema baseline (Story 13.9).</b> A converted module may declare an {@link ExistingSchemaBaseline}:
 * on a schema without the module's history table but with its probe table, the baseline version is that one (the
 * scripts up to it are not executed — the existing tables are adopted untouched) instead of {@code 0}.
 *
 * <p><b>Migration policy (Story 13.9).</b> {@code validateOnMigrate} on, {@code outOfOrder} off: an applied script
 * is immutable — editing it makes the next provisioning fail (checksum mismatch, {@code ModuleProvisioningException},
 * module unchanged) until the original is restored; a change always ships as a new versioned script. The manual
 * {@code flyway repair} procedure is documented in the 13.9 spec. {@code core} ships no migration (its tables are the
 * core DDL), so it has no history table.
 *
 * <p>Concurrency: Flyway's lock on the history table serialises concurrent runs on the same (schema, module); each
 * migration runs in its own transaction (PostgreSQL DDL is transactional), so a failed script leaves the previous
 * version in place — the failure reports the highest version applied so far.
 */
@Component
public class FlywayModuleMigrations implements ModuleMigrationPort {

    /** Classpath root of the module migrations: {@code db/modules/<moduleId>/}. */
    public static final String DEFAULT_ROOT = "db/modules";
    static final String HISTORY_TABLE_PREFIX = "flyway_history_";
    /** PostgreSQL identifier limit (NAMEDATALEN - 1). */
    static final int MAX_IDENTIFIER_LENGTH = 63;
    /** Flyway default naming: versioned {@code V<version>__<desc>.sql} or repeatable {@code R__<desc>.sql}. */
    static final Pattern FLYWAY_SCRIPT = Pattern.compile("^(V[0-9]+([._][0-9]+)*__.+|R__.+)\\.sql$");

    private final DataSource dataSource;
    private final List<String> roots;
    private final ResourcePatternResolver resources;

    @Autowired
    public FlywayModuleMigrations(DataSource dataSource) {
        this(dataSource, List.of(DEFAULT_ROOT));
    }

    /**
     * Test seam: several classpath roots, each holding {@code <moduleId>/} folders (Flyway merges the locations,
     * e.g. a "next release" root that adds {@code V3}).
     */
    public FlywayModuleMigrations(DataSource dataSource, List<String> roots) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("At least one migration root is required");
        }
        this.dataSource = dataSource;
        this.roots = List.copyOf(roots);
        this.resources = new PathMatchingResourcePatternResolver(FlywayModuleMigrations.class.getClassLoader());
    }

    /** The module id, checked against the manifest id rule before any interpolation (raw value never echoed). */
    static String validateModuleId(String moduleId) {
        if (moduleId == null || moduleId.length() > ModuleManifestLoader.ID_MAX
                || !ModuleManifestLoader.ID_PATTERN.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("Invalid module id (length="
                    + (moduleId == null ? "null" : String.valueOf(moduleId.length())) + ")");
        }
        return moduleId;
    }

    /** {@code flyway_history_<id>} with {@code -} → {@code _}; longer than a PostgreSQL identifier → IAE. */
    static String historyTable(String moduleId) {
        String table = HISTORY_TABLE_PREFIX + validateModuleId(moduleId).replace('-', '_');
        if (table.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("Module id too long for a history table (length=" + moduleId.length() + ")");
        }
        return table;
    }

    /**
     * Whether a Flyway-named script exists in the module's location (recursively, every classpath entry — what
     * Flyway's {@code classpath:} location scans). Misnamed files do not count: a folder holding only misnamed files
     * is a module without migrations (no-op); a misnamed file beside valid ones fails at migration time.
     */
    @Override
    public boolean hasMigrations(String moduleId) {
        String id = validateModuleId(moduleId);
        for (String root : roots) {
            try {
                for (Resource resource : resources.getResources("classpath*:" + root + "/" + id + "/**/*.sql")) {
                    String name = resource.getFilename();
                    if (resource.exists() && name != null && FLYWAY_SCRIPT.matcher(name).matches()) {
                        return true;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot scan the migrations of a module", e);
            }
        }
        return false;
    }

    /** {@code V<version>__} prefix of a versioned script (version part captured). */
    static final Pattern VERSIONED_SCRIPT = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");

    /** Story 14.1 — highest {@code V<version>__} script of the module across the roots; {@code null} when none. */
    @Override
    public String latestVersion(String moduleId) {
        String id = validateModuleId(moduleId);
        MigrationVersion highest = null;
        for (String root : roots) {
            try {
                for (Resource resource : resources.getResources("classpath*:" + root + "/" + id + "/**/*.sql")) {
                    String name = resource.getFilename();
                    java.util.regex.Matcher m = name == null ? null : VERSIONED_SCRIPT.matcher(name);
                    if (m != null && m.matches()) {
                        MigrationVersion version = MigrationVersion.fromVersion(m.group(1).replace('_', '.'));
                        if (highest == null || version.compareTo(highest) > 0) {
                            highest = version;
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot scan the migrations of a module", e);
            }
        }
        return highest == null ? null : highest.getVersion();
    }

    /** Story 14.1 — the schema exists ({@code information_schema.schemata}, bind parameter). */
    @Override
    public boolean schemaExists(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new ModuleProvisioningException("schema", schema, "SCHEMA/" + failureCode(e), null);
        }
    }

    /**
     * Story 14.1 — highest successful version in the module's history table of the schema (a {@code 0} baseline row
     * ignored), {@code null} when the table does not exist or holds none. Read-only, on a pooled connection (fully
     * qualified names, the connection's {@code search_path} is not touched).
     */
    @Override
    public String currentVersion(String tenantSchema, String moduleId) {
        String schema = TenantSchema.validate(tenantSchema);
        String table = historyTable(moduleId);
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, schema, table)) {
                return null;
            }
            String sql = "SELECT version, type FROM \"" + schema + "\"." + table
                    + " WHERE success AND version IS NOT NULL";
            MigrationVersion highest = null;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    MigrationVersion version = MigrationVersion.fromVersion(rows.getString(1));
                    boolean emptyBaseline = "BASELINE".equals(rows.getString(2))
                            && version.compareTo(MigrationVersion.fromVersion("0")) <= 0;
                    if (!emptyBaseline && (highest == null || version.compareTo(highest) > 0)) {
                        highest = version;
                    }
                }
            }
            return highest == null ? null : highest.getVersion();
        } catch (SQLException e) {
            throw new ModuleProvisioningException(moduleId, schema, "HISTORY/" + failureCode(e), null);
        }
    }

    @Override
    public Outcome migrate(String tenantSchema, String moduleId, ExistingSchemaBaseline baseline) {
        String schema = TenantSchema.validate(tenantSchema);
        String table = historyTable(moduleId);
        String[] locations = roots.stream().map(root -> "classpath:" + root + "/" + moduleId).toArray(String[]::new);
        DataSource flywayDataSource = dedicatedDataSource();
        String baselineVersion = baselineVersion(flywayDataSource, schema, table, baseline, moduleId);
        Flyway flyway = Flyway.configure()
                .dataSource(flywayDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .table(table)
                .locations(locations)
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .createSchemas(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true) // Story 13.9: an edited applied script fails loudly (checksum mismatch)
                .outOfOrder(false)       // Story 13.9: a version lower than the applied one is never run late
                .load();
        String from = null;
        try {
            from = highestApplied(flyway.info().applied());
            MigrateResult result = flyway.migrate();
            String to = highestApplied(flyway.info().applied());
            return new Outcome(from, to, result.migrationsExecuted);
        } catch (RuntimeException e) {
            throw new ModuleProvisioningException(moduleId, schema, failureCode(e), appliedAfterFailure(flyway, from));
        }
    }

    /**
     * Story 13.9 — {@code "0"} (every script runs), or the module's existing-schema baseline version when its history
     * table does not exist yet and its probe table already exists in the schema (the existing tables are adopted
     * as they are: Flyway records a baseline at that version and runs only the later scripts).
     */
    static String baselineVersion(DataSource dataSource, String schema, String historyTable,
                                  ExistingSchemaBaseline baseline, String moduleId) {
        if (baseline == null) {
            return "0";
        }
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection, schema, historyTable) || !tableExists(connection, schema, baseline.probeTable())) {
                return "0";
            }
            return baseline.version();
        } catch (SQLException e) {
            throw new ModuleProvisioningException(moduleId, schema, "PROBE/" + failureCode(e), null);
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Highest version applied after a failure (earlier scripts of the run committed); {@code from} if unreadable. */
    private static String appliedAfterFailure(Flyway flyway, String from) {
        try {
            return highestApplied(flyway.info().applied());
        } catch (RuntimeException ignored) {
            return from;
        }
    }

    /** Flyway error code and/or SQL state, else the exception class — never a message (it holds SQL / schema). */
    static String failureCode(Throwable e) {
        StringBuilder code = new StringBuilder();
        if (e instanceof FlywayException flyway && flyway.getErrorCode() != null) {
            code.append(flyway.getErrorCode());
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                code.append(code.isEmpty() ? "" : "/").append(sql.getSQLState());
                break;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return code.isEmpty() ? e.getClass().getSimpleName() : code.toString();
    }

    /** A non-pooled DataSource for Flyway (see class doc). */
    DataSource dedicatedDataSource() {
        if (dataSource instanceof AbstractDriverBasedDataSource
                && !(dataSource instanceof SmartDataSource)) {
            return dataSource; // already non-pooled: every connection is physical (SingleConnectionDataSource refused)
        }
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
                if (pool.getJdbcUrl() == null) {
                    throw new IllegalStateException("The connection pool has no JDBC URL");
                }
                return new DriverManagerDataSource(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot unwrap the application DataSource", e);
        }
        throw new IllegalStateException("Unsupported DataSource for module migrations: "
                + dataSource.getClass().getSimpleName());
    }

    /**
     * Highest successfully applied versioned migration; a baseline row counts only above {@code 0} (an adopted
     * existing schema, Story 13.9 — the {@code 0} baseline of a fresh history means "nothing"); {@code null} when none.
     */
    static String highestApplied(MigrationInfo[] applied) {
        MigrationVersion highest = null;
        for (MigrationInfo info : applied) {
            MigrationVersion version = info.getVersion();
            MigrationState state = info.getState();
            boolean emptyBaseline = state == MigrationState.BASELINE
                    && version != null && version.compareTo(MigrationVersion.fromVersion("0")) <= 0;
            if (version == null || emptyBaseline || state.isFailed() || !state.isApplied()) {
                continue;
            }
            if (highest == null || version.compareTo(highest) > 0) {
                highest = version;
            }
        }
        return highest == null ? null : highest.getVersion();
    }
}
