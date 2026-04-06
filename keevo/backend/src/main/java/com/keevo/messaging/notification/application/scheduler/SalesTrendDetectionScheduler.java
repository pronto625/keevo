package com.keevo.messaging.notification.application.scheduler;

import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
    private final NotificationPort notificationPort;
    private final NotificationCooldownRepository cooldownRepository;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final StoreRepository storeRepository;

    public SalesTrendDetectionScheduler(JdbcTemplate jdbcTemplate,
                                        NotificationPort notificationPort,
                                        NotificationCooldownRepository cooldownRepository,
                                        TenantPreferencesRepository tenantPreferencesRepository,
                                        StoreRepository storeRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationPort = notificationPort;
        this.cooldownRepository = cooldownRepository;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.storeRepository = storeRepository;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void detectTrends() {
        log.info("[TREND] Starting hourly sales trend detection");
        List<String> schemas = jdbcTemplate.queryForList(FETCH_ACTIVE_SCHEMAS, String.class);

        for (String schema : schemas) {
            if (!schema.matches("^kv_[a-z0-9]{6}$")) continue;
            try {
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

    private void processTenant(String schema) {
        TenantPreferences prefs = tenantPreferencesRepository.findByCurrentTenant().orElse(null);
        if (prefs == null || !prefs.trendNotificationEnabled()) {
            log.debug("[TREND] Trend notifications disabled for tenant={}", schema);
            return;
        }

        List<Store> stores = storeRepository.findAllActive();
        int currentHour = LocalTime.now(ZoneOffset.UTC).getHour();

        for (Store store : stores) {
            try {
                analyzeStoreTrend(schema, store, currentHour);
            } catch (Exception e) {
                log.warn("[TREND] Error analyzing store {} in tenant {}: {}",
                        store.id(), schema, e.getMessage());
            }
        }
    }

    private void analyzeStoreTrend(String schema, Store store, int currentHour) {
        String qualifiedSales = "\"" + schema + "\".sales";

        // Count sales in the just-completed hour (cron fires at HH:00 UTC; the window
        // [HH-1:00, HH:00) is the last full hour of data — avoids false TREND_DOWN at fire time)
        int prevHour = (currentHour == 0) ? 23 : currentHour - 1;
        Integer currentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedSales +
                " WHERE status = 'COMPLETED' AND store_id = ?" +
                " AND occurred_at >= date_trunc('hour', NOW()) - INTERVAL '1 hour'" +
                " AND occurred_at < date_trunc('hour', NOW())",
                Integer.class, store.id());

        if (currentCount == null) currentCount = 0;

        // Average over same hour on previous 3 days
        @SuppressWarnings("SqlSourceToSinkFlow")
        Double avgCount = jdbcTemplate.queryForObject(
                "SELECT AVG(hourly_count) FROM (" +
                "  SELECT COUNT(*) AS hourly_count FROM " + qualifiedSales +
                "  WHERE status = 'COMPLETED' AND store_id = ?" +
                "  AND occurred_at >= date_trunc('hour', NOW()) - INTERVAL '1 day'" +
                "  AND occurred_at <  date_trunc('hour', NOW()) - INTERVAL '1 day' + INTERVAL '1 hour'" +
                " UNION ALL" +
                "  SELECT COUNT(*) FROM " + qualifiedSales +
                "  WHERE status = 'COMPLETED' AND store_id = ?" +
                "  AND occurred_at >= date_trunc('hour', NOW()) - INTERVAL '2 days'" +
                "  AND occurred_at <  date_trunc('hour', NOW()) - INTERVAL '2 days' + INTERVAL '1 hour'" +
                " UNION ALL" +
                "  SELECT COUNT(*) FROM " + qualifiedSales +
                "  WHERE status = 'COMPLETED' AND store_id = ?" +
                "  AND occurred_at >= date_trunc('hour', NOW()) - INTERVAL '3 days'" +
                "  AND occurred_at <  date_trunc('hour', NOW()) - INTERVAL '3 days' + INTERVAL '1 hour'" +
                ") sub",
                Double.class, store.id(), store.id(), store.id());

        if (avgCount == null || avgCount < 1.0) {
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
                notificationPort.notifyOwners(schema, payload);
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
                notificationPort.notifyOwners(schema, payload);
            } finally {
                TenantContext.setCurrentTenant(schema); // FCM clears context; restore for next store
            }
            cooldownRepository.upsertCooldown(COOLDOWN_TYPE_TREND_UP, null, store.id());
            log.info("[TREND] Positive trend detected for store={} in tenant={} ratio={}", store.name(), schema, trendRatio);
        }
    }
}
