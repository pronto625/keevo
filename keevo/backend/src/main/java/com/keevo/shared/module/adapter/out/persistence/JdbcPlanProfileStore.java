package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.module.application.port.PlanProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.8 — JDBC adapter of {@link PlanProfiles} (live read) and {@link PlanProfileStore} (super-admin write) on
 * {@code public.plan_profiles} + {@code public.profile_modules}. No cache: each read is one query (primary key +
 * left join).
 *
 * <p>The live read ({@link #profile}) runs on a <b>dedicated connection</b>, outside the caller's transaction, with a
 * short timeout: a fault there can never abort the caller's PostgreSQL transaction, so the fallback is usable. Read
 * fault or missing row → the {@link PlanType} constants ({@link PlanProfile#fromEnum}) with an ERROR log, never an
 * exception. It sees committed profiles only (the super-admin editor commits before re-syncing tenants). The write
 * side ({@link PlanProfileStore}) runs on the caller's transaction.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcPlanProfileStore implements PlanProfiles, PlanProfileStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlanProfileStore.class);

    static final String FIND_SQL = """
            SELECT p.plan_type, p.max_stores, p.max_products, p.max_employees, pm.module_id, pm.granted
            FROM public.plan_profiles p
            LEFT JOIN public.profile_modules pm ON pm.plan_type = p.plan_type
            WHERE p.plan_type = ?
            """;

    static final String LOCK_SQL = "SELECT plan_type FROM public.plan_profiles WHERE plan_type = ? FOR UPDATE";

    static final String UPSERT_SQL = """
            INSERT INTO public.plan_profiles (plan_type, max_stores, max_products, max_employees, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (plan_type) DO UPDATE SET max_stores = EXCLUDED.max_stores,
                max_products = EXCLUDED.max_products, max_employees = EXCLUDED.max_employees,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """;

    static final String DELETE_MODULES_SQL = "DELETE FROM public.profile_modules WHERE plan_type = ?";
    static final String INSERT_MODULE_SQL =
            "INSERT INTO public.profile_modules (plan_type, module_id, granted) VALUES (?, ?, ?)";

    /** Seconds the live read may take before the fallback is used. */
    static final int QUERY_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcPlanProfileStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public PlanProfile profile(PlanType planType) {
        try (Connection connection = dataSource.getConnection()) {
            Optional<PlanProfile> found = read(connection, planType);
            if (found.isPresent()) {
                return found.get();
            }
            log.error("Plan profiles: no profile stored for plan {} — built-in defaults used", planType);
        } catch (SQLException | RuntimeException e) {
            log.error("Plan profiles: profile of plan {} unreadable ({}: {}) — built-in defaults used", planType,
                    e.getClass().getSimpleName(), e.getMessage());
        }
        return PlanProfile.fromEnum(planType);
    }

    @Override
    public Optional<PlanProfile> findForUpdate(PlanType planType) {
        List<String> locked = jdbc.queryForList(LOCK_SQL, String.class, planType.name());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Optional<PlanProfile>>)
                connection -> read(connection, planType));
    }

    @Override
    public void save(PlanProfile profile, UUID actorId) {
        String type = profile.planType().name();
        jdbc.update(UPSERT_SQL, type, profile.maxStores(), profile.maxProducts(), profile.maxEmployees(),
                Timestamp.from(Instant.now()), actorId);
        jdbc.update(DELETE_MODULES_SQL, type);
        for (String moduleId : profile.modules()) {
            jdbc.update(INSERT_MODULE_SQL, type, moduleId, true);
        }
        for (String moduleId : profile.withheld()) {
            jdbc.update(INSERT_MODULE_SQL, type, moduleId, false);
        }
    }

    private static Optional<PlanProfile> read(Connection connection, PlanType planType) throws SQLException {
        Integer[] limits = new Integer[3];
        boolean found = false;
        Set<String> granted = new HashSet<>();
        Set<String> withheld = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(FIND_SQL)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, planType.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found = true;
                    limits[0] = nullableInt(rs, "max_stores");
                    limits[1] = nullableInt(rs, "max_products");
                    limits[2] = nullableInt(rs, "max_employees");
                    String moduleId = rs.getString("module_id");
                    if (moduleId != null) {
                        (rs.getBoolean("granted") ? granted : withheld).add(moduleId);
                    }
                }
            }
        }
        if (!found) {
            return Optional.empty();
        }
        return Optional.of(PlanProfile.of(planType, limits[0], limits[1], limits[2], granted, withheld));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
