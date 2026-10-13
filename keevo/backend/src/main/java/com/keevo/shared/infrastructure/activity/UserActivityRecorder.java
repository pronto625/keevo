package com.keevo.shared.infrastructure.activity;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * UserActivityRecorder — records that a user was active on a WAT calendar day (Story 9-4, DAU/MAU).
 *
 * <p>Called by {@code JwtAuthFilter} after a successful authentication. Contract:
 * <ul>
 *   <li>at most one {@code public.user_daily_activity} row per (user, tenant, WAT day) — an in-memory
 *       set of the keys already written today short-circuits every later call of the same day, and the
 *       insert is {@code ON CONFLICT DO NOTHING} (multi-instance / restart safe);</li>
 *   <li>the insert never runs on the request thread: it is queued on a bounded single-thread executor;
 *       a full queue drops the record (retried on the user's next request);</li>
 *   <li>{@code SUPER_ADMIN} calls are never recorded;</li>
 *   <li>a failure (queue full, DB error…) is logged at debug and never reaches the request.</li>
 * </ul>
 *
 * <p>The JWT carries the tenant schema name, not the tenant id: the id is resolved inside the same
 * asynchronous insert ({@code SELECT t.id FROM public.tenants t WHERE t.schema_name = ?}), i.e. at most
 * once per user and day, never as an extra query on the request path.
 */
@Component
public class UserActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(UserActivityRecorder.class);

    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    static final int QUEUE_CAPACITY = 10_000;

    static final String INSERT_SQL =
            "INSERT INTO public.user_daily_activity (user_id, tenant_id, activity_date) "
            + "SELECT ?, t.id, ? FROM public.tenants t WHERE t.schema_name = ? "
            + "ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Executor executor;

    /** Keys ({@code day|user|schema}) already written (or queued) for {@link #currentDay}. */
    private final Set<String> recorded = ConcurrentHashMap.newKeySet();
    private volatile LocalDate currentDay;

    @Autowired
    public UserActivityRecorder(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, newBoundedExecutor());
    }

    /** Test constructor — injectable executor. */
    UserActivityRecorder(JdbcTemplate jdbc, Clock clock, Executor executor) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * Records the activity of {@code userId} in the tenant whose schema is {@code tenantSchema}.
     * Never throws.
     */
    public void record(UUID userId, String tenantSchema, String role) {
        try {
            if (userId == null || "SUPER_ADMIN".equals(role) || !TenantSchema.isValid(tenantSchema)) {
                return;
            }
            LocalDate today = LocalDate.now(clock.withZone(WAT));
            rollOver(today);
            String key = today + "|" + userId + "|" + tenantSchema;
            if (!recorded.add(key)) {
                return;
            }
            try {
                executor.execute(() -> insert(key, userId, today, tenantSchema));
            } catch (RuntimeException rejected) {
                // Queue full / executor shut down: forget the key so a later request retries.
                recorded.remove(key);
                log.debug("User activity not queued ({})", rejected.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            log.debug("User activity not recorded ({})", e.getClass().getSimpleName());
        }
    }

    private void insert(String key, UUID userId, LocalDate day, String tenantSchema) {
        try {
            jdbc.update(INSERT_SQL, userId, day, tenantSchema);
        } catch (RuntimeException e) {
            recorded.remove(key);
            log.debug("User activity insert failed ({})", e.getClass().getSimpleName());
        }
    }

    /** New WAT day: drop yesterday's keys (each key carries its day, so a racing add stays correct). */
    private void rollOver(LocalDate today) {
        if (today.equals(currentDay)) return;
        synchronized (this) {
            if (today.equals(currentDay)) return;
            String prefix = today + "|";
            recorded.removeIf(k -> !k.startsWith(prefix));
            currentDay = today;
        }
    }

    /** Number of keys held in memory (tests / diagnostics). */
    int trackedKeys() {
        return recorded.size();
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    private static ExecutorService newBoundedExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "user-activity-recorder");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return pool;
    }
}
