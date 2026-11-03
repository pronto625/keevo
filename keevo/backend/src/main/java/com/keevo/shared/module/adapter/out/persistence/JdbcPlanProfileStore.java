package com.keevo.shared.module.adapter.out.persistence;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story 16.8 — JDBC adapter of {@link PlanProfiles} (live read) and {@link PlanProfileStore} (super-admin write) on
 * {@code public.plan_profiles} + {@code public.profile_modules}. No cache: each read is one query (primary key +
 * left join).
 *
 * <p>The live read ({@link #profile}) runs on a <b>dedicated connection</b>, outside the caller's transaction, with a
 * short timeout: a fault there can never abort the caller's PostgreSQL transaction, so the fallback is usable. It
 * sees committed profiles only (the super-admin editor commits before re-syncing tenants). The write side
 * ({@link PlanProfileStore}) runs on the caller's transaction.
 *
 * <p>Story 19.7a — the plan's kind comes from the catalogue ({@link PlanCatalog}) and no limit lives in the code:
 * <ul>
 *   <li>plan without a stored profile → the free plan's profile ({@link PlanProfile#fallbackFrom}, fail closed for
 *       paid limits and modules), ERROR log;</li>
 *   <li>read fault → the last profile read for that plan (else the free plan's last one), ERROR log;</li>
 *   <li>nothing ever read (database down since boot) → {@link PlanProfile#unavailable} (no limit), ERROR log.</li>
 * </ul>
 * Never an exception. The first read loads every profile (one query) so a later fault has something to fall back
 * on.
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

    static final String FIND_ALL_SQL = """
            SELECT p.plan_type, p.max_stores, p.max_products, p.max_employees, pm.module_id, pm.granted
            FROM public.plan_profiles p
            LEFT JOIN public.profile_modules pm ON pm.plan_type = p.plan_type
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
    private final PlanCatalog catalog;
    /** Last profile read per plan code: the fallback of a read fault (never a source of decisions otherwise). */
    private final Map<String, PlanProfile> lastRead = new ConcurrentHashMap<>();
    private volatile boolean warmed;

    public JdbcPlanProfileStore(DataSource dataSource, PlanCatalog catalog) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.catalog = catalog;
    }

    @Override
    public PlanProfile profile(PlanType planType) {
        // catalogue lookups first: never while a pool connection is held (a catalogue reload needs its own)
        Map<String, PlanKind> kinds = kinds();
        PlanType free = catalog.freePlan();
        PlanKind kind = kindOf(planType, kinds);
        try (Connection connection = dataSource.getConnection()) {
            warm(connection, kinds);
            Optional<PlanProfile> found = read(connection, planType, kind);
            if (found.isPresent()) {
                lastRead.put(planType.code(), found.get());
                return found.get();
            }
            if (!free.equals(planType)) {
                Optional<PlanProfile> freeProfile = read(connection, free, kindOf(free, kinds));
                if (freeProfile.isPresent()) {
                    lastRead.put(free.code(), freeProfile.get());
                    log.error("Plan profiles: no profile stored for plan {} — the free plan's profile is used",
                            planType);
                    return PlanProfile.fallbackFrom(planType, freeProfile.get());
                }
            }
            log.error("Plan profiles: no profile stored for plan {} — last profile read used", planType);
        } catch (SQLException | RuntimeException e) {
            log.error("Plan profiles: profile of plan {} unreadable ({}: {}) — last profile read used", planType,
                    e.getClass().getSimpleName(), e.getMessage());
        }
        return lastKnown(planType, kind, free);
    }

    /**
     * Every requested profile in one query ({@link #FIND_ALL_SQL}), with the same fallbacks as {@link #profile}: a
     * plan without a stored row gets the free plan's profile (ERROR), a read fault the last profile read.
     */
    @Override
    public Map<PlanType, PlanProfile> profiles(Collection<PlanType> planTypes) {
        Map<String, PlanKind> kinds = kinds();
        PlanType free = catalog.freePlan();
        Map<String, PlanProfile> stored = null;
        try (Connection connection = dataSource.getConnection()) {
            stored = readAll(connection, kinds);
            stored.forEach(lastRead::put);
            warmed = true;
        } catch (SQLException | RuntimeException e) {
            log.error("Plan profiles: profiles unreadable ({}: {}) — last profiles read used",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        Map<PlanType, PlanProfile> result = new LinkedHashMap<>();
        for (PlanType planType : planTypes) {
            PlanKind kind = kindOf(planType, kinds);
            PlanProfile own = stored != null ? stored.get(planType.code()) : null;
            PlanProfile freeProfile = stored != null ? stored.get(free.code()) : null;
            if (own != null) {
                result.put(planType, own);
            } else if (freeProfile != null && !free.equals(planType)) {
                log.error("Plan profiles: no profile stored for plan {} — the free plan's profile is used", planType);
                result.put(planType, PlanProfile.fallbackFrom(planType, freeProfile));
            } else {
                result.put(planType, lastKnown(planType, kind, free));
            }
        }
        return result;
    }

    /** Code → kind of every catalogue plan (one snapshot lookup). */
    private Map<String, PlanKind> kinds() {
        Map<String, PlanKind> kinds = new HashMap<>();
        catalog.all().forEach(p -> kinds.put(p.code().code(), p.kind()));
        return kinds;
    }

    /** The plan's kind; a code the catalogue does not know is treated as free (fail closed). */
    private static PlanKind kindOf(PlanType planType, Map<String, PlanKind> kinds) {
        PlanKind kind = kinds.get(planType.code());
        if (kind == null) {
            log.error("Plan profiles: plan {} is not in the catalogue — treated as free", planType);
            return PlanKind.FREE;
        }
        return kind;
    }

    private PlanProfile lastKnown(PlanType planType, PlanKind kind, PlanType freePlan) {
        PlanProfile own = lastRead.get(planType.code());
        if (own != null) {
            return PlanProfile.fallbackFrom(planType, own);
        }
        PlanProfile free = lastRead.get(freePlan.code());
        if (free != null) {
            return PlanProfile.fallbackFrom(planType, free);
        }
        log.error("Plan profiles: no profile of plan {} ever read — no limit applied", planType);
        return PlanProfile.unavailable(planType, kind);
    }

    /**
     * First read: every stored profile (one query), so a later fault has a last-read profile to fall back on. Its own
     * failure only logs: the targeted read goes on.
     */
    private void warm(Connection connection, Map<String, PlanKind> kinds) {
        if (warmed) {
            return;
        }
        try {
            readAll(connection, kinds).forEach(lastRead::putIfAbsent);
            warmed = true;
        } catch (SQLException | RuntimeException e) {
            log.warn("Plan profiles: warm-up read failed ({}: {}) — retried on the next read",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Every stored profile, by plan code (one query). */
    private static Map<String, PlanProfile> readAll(Connection connection, Map<String, PlanKind> kinds)
            throws SQLException {
        Map<String, Integer[]> limits = new HashMap<>();
        Map<String, Set<String>> granted = new HashMap<>();
        Map<String, Set<String>> withheld = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(FIND_ALL_SQL)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("plan_type");
                    limits.put(code, new Integer[] {nullableInt(rs, "max_stores"), nullableInt(rs, "max_products"),
                            nullableInt(rs, "max_employees")});
                    String moduleId = rs.getString("module_id");
                    if (moduleId != null) {
                        (rs.getBoolean("granted") ? granted : withheld)
                                .computeIfAbsent(code, k -> new HashSet<>()).add(moduleId);
                    }
                }
            }
        }
        Map<String, PlanProfile> all = new HashMap<>();
        limits.forEach((code, l) -> PlanType.parse(code).ifPresent(type -> all.put(code,
                PlanProfile.of(type, kindOf(type, kinds), l[0], l[1], l[2], granted.get(code), withheld.get(code)))));
        return all;
    }

    @Override
    public Optional<PlanProfile> findForUpdate(PlanType planType) {
        PlanKind kind = kindOf(planType, kinds()); // before any statement on the caller's connection
        List<String> locked = jdbc.queryForList(LOCK_SQL, String.class, planType.name());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Optional<PlanProfile>>)
                connection -> read(connection, planType, kind));
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

    private static Optional<PlanProfile> read(Connection connection, PlanType planType, PlanKind kind)
            throws SQLException {
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
        return Optional.of(PlanProfile.of(planType, kind, limits[0], limits[1], limits[2], granted, withheld));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
