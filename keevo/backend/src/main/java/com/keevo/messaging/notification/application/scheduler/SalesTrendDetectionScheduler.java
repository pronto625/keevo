package com.keevo.messaging.notification.application.scheduler;

import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.messaging.notification.domain.port.out.StoreActivityRepository;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ModuleStateScope;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreQueryPort;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SalesTrendDetectionScheduler — hourly cron job that detects abnormal sales
 * trends (positive spikes or negative drops) and sends push notifications.
 *
 * <p>For each active tenant, compares the current hour's sales count against
 * the average of the same hour over the previous 3 days.
 *
 * <p>Story 8.1 — AC3/AC4.
 *
 * <p>Story 20.14: activity = transactions of the single revenue source (sales and invoices of the store, a sale
 * bridged to an invoice counted once), read in one grouped query per tenant; the previous days' windows are the
 * same hour as the compared one (they were the next hour before).
 *
 * <p>Story 13.10a: sales trends belong to the paid « Rapports avancés » module (plan §6.1) — a tenant for which
 * it is DISABLED is skipped (READ_ONLY still runs: it only reads past sales); an unreadable state is logged at
 * WARN and the tenant processed.
 */
@Component
public class SalesTrendDetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SalesTrendDetectionScheduler.class);

    private static final String COOLDOWN_TYPE_TREND_DOWN = "TREND_DOWN";
    private static final String COOLDOWN_TYPE_TREND_UP = "TREND_UP";
    private static final Duration TREND_COOLDOWN = Duration.ofHours(2);
    private static final double DROP_THRESHOLD = 0.60;
    private static final double SPIKE_THRESHOLD = 1.60;

    private static final String FETCH_ACTIVE_SCHEMAS =
            "SELECT schema_name FROM public.tenants WHERE status = 'ACTIVE'";

    private final JdbcTemplate jdbcTemplate;
    private final PushNotifier pushNotifier;
    private final NotificationCooldownRepository cooldownRepository;
    private final TenantPreferencesQueryPort preferencesQuery;
    private final StoreQueryPort storeQuery;
    /** Story 13.10a — {@code null} only in unit tests built without it (no gate). */
    private final ModuleRegistry modules;
    /** Story 20.14 — hourly store activity on the single revenue source (sales and invoices). */
    private final StoreActivityRepository activity;

    static final String ADVANCED_REPORTS = "advanced-reports";

    public SalesTrendDetectionScheduler(JdbcTemplate jdbcTemplate,
                                        PushNotifier pushNotifier,
                                        NotificationCooldownRepository cooldownRepository,
                                        TenantPreferencesQueryPort preferencesQuery,
                                        StoreQueryPort storeQuery,
                                        StoreActivityRepository activity) {
        this(jdbcTemplate, pushNotifier, cooldownRepository, preferencesQuery, storeQuery, null, activity);
    }

    @Autowired
    public SalesTrendDetectionScheduler(JdbcTemplate jdbcTemplate,
                                        PushNotifier pushNotifier,
                                        NotificationCooldownRepository cooldownRepository,
                                        TenantPreferencesQueryPort preferencesQuery,
                                        StoreQueryPort storeQuery,
                                        ModuleRegistry modules,
                                        StoreActivityRepository activity) {
        this.activity = activity;
        this.modules = modules;
        this.jdbcTemplate = jdbcTemplate;
        this.pushNotifier = pushNotifier;
        this.cooldownRepository = cooldownRepository;
        this.preferencesQuery = preferencesQuery;
        this.storeQuery = storeQuery;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @SchedulerLock(name = "salesTrendDetectionScheduler.detectTrends", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void detectTrends() {
        log.info("[TREND] Starting hourly sales trend detection");
        List<String> schemas = jdbcTemplate.queryForList(FETCH_ACTIVE_SCHEMAS, String.class);

        for (String schema : schemas) {
            if (!TenantSchema.isValid(schema)) continue;
            // one module-state lookup per tenant, shared by the module gate and the preferences port
            try (var moduleStates = ModuleStateScope.open()) {
                if (advancedReportsDisabled(schema)) continue;
                TenantContext.setCurrentTenant(schema);
                processTenant(schema);
            } catch (Exception e) {
                log.error("[TREND] Error in tenant {}: {}", schema, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        log.info("[TREND] Hourly trend detection completed for {} tenants", schemas.size());
    }

    private boolean advancedReportsDisabled(String schema) {
        if (modules == null) {
            return false;
        }
        try {
            if (modules.moduleState(schema, ADVANCED_REPORTS) == ModuleState.DISABLED) {
                log.debug("[TREND] Module « Rapports avancés » disabled for tenant {} — skipped", schema);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("[TREND] Module state unreadable for tenant {} ({}) — processed", schema, e.getClass().getSimpleName());
            return false;
        }
    }

    private void processTenant(String schema) {
        if (!TenantSchema.isValid(schema)) { // detectTrends() filters; re-checked once per tenant
            log.warn("[TREND] Tenant skipped (invalid schema name)");
            return;
        }
        TenantPreferencesView prefs = preferencesQuery.find(TenantContext.getCurrentTenant()).orElse(null);
        if (prefs == null || !prefs.trendNotificationEnabled()) {
            log.debug("[TREND] Trend notifications disabled for tenant={}", schema);
            return;
        }

        List<StoreView> stores = storeQuery.findAllActive(TenantContext.getCurrentTenant());
        if (stores.isEmpty()) {
            return;
        }
        // cron fires at HH:00 UTC: the last full hour [HH-1:00, HH:00) vs the same hour of the 3 previous days.
        // Story 20.14 — one grouped query for every store (sales and invoices, single revenue source)
        Instant hourEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        int prevHour = LocalTime.ofInstant(hourEnd.minus(Duration.ofHours(1)), ZoneOffset.UTC).getHour();
        Map<UUID, StoreActivityRepository.HourlyActivity> byStore = activity.lastHour(hourEnd);

        for (StoreView store : stores) {
            try {
                analyzeStoreTrend(schema, store, prevHour,
                        byStore.getOrDefault(store.id(), StoreActivityRepository.HourlyActivity.NONE));
            } catch (Exception e) {
                log.warn("[TREND] Error analyzing store {} in tenant {}: {}",
                        store.id(), schema, e.getMessage());
            }
        }
    }

    private void analyzeStoreTrend(String schema, StoreView store, int prevHour,
                                   StoreActivityRepository.HourlyActivity hourly) {
        int currentCount = hourly.current();
        double avgCount = hourly.previousDaysAverage();

        if (avgCount < 1.0) {
            log.debug("[TREND] Insufficient history for store={} tenant={} (avg={})", store.id(), schema, avgCount);
            return;
        }

        double trendRatio = currentCount / avgCount;

        if (trendRatio < DROP_THRESHOLD) {
            // Negative spike
            if (cooldownRepository.existsActiveCooldown(COOLDOWN_TYPE_TREND_DOWN, null, store.id(), TREND_COOLDOWN)) {
                return;
            }
            NotificationPayload payload = NotificationPayload.of(
                    "TREND_DOWN",
                    "\uD83D\uDCC9 Baisse des ventes détectée",
                    String.format("Baisse des ventes détectée — %s : activité inhabituelle depuis %dh", store.name(), prevHour),
                    "/reports/history",
                    Map.of(
                            "storeId", store.id().toString(),
                            "storeName", store.name(),
                            "trendType", "DOWN",
                            "trendRatio", String.format("%.2f", trendRatio)
                    )
            );
            try {
                pushNotifier.notifyOwners(schema, payload);
            } finally {
                TenantContext.setCurrentTenant(schema); // FCM clears context; restore for next store
            }
            cooldownRepository.upsertCooldown(COOLDOWN_TYPE_TREND_DOWN, null, store.id());
            log.info("[TREND] Negative trend detected for store={} in tenant={} ratio={}", store.name(), schema, trendRatio);

        } else if (trendRatio > SPIKE_THRESHOLD) {
            // Positive spike
            if (cooldownRepository.existsActiveCooldown(COOLDOWN_TYPE_TREND_UP, null, store.id(), TREND_COOLDOWN)) {
                return;
            }
            NotificationPayload payload = NotificationPayload.of(
                    "TREND_UP",
                    "\uD83D\uDE80 Pic de ventes",
                    String.format("Pic de ventes — %s : %d vente(s) cette heure — continuez sur cette lancée !", store.name(), currentCount),
                    "/reports/history",
                    Map.of(
                            "storeId", store.id().toString(),
                            "storeName", store.name(),
                            "trendType", "UP",
                            "currentCount", String.valueOf(currentCount)
                    )
            );
            try {
                pushNotifier.notifyOwners(schema, payload);
            } finally {
                TenantContext.setCurrentTenant(schema); // FCM clears context; restore for next store
            }
            cooldownRepository.upsertCooldown(COOLDOWN_TYPE_TREND_UP, null, store.id());
            log.info("[TREND] Positive trend detected for store={} in tenant={} ratio={}", store.name(), schema, trendRatio);
        }
    }
}
