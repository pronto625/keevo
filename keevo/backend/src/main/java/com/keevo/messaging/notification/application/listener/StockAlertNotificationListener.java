package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockAlertNotificationListener — dispatches push and/or WhatsApp notifications
 * when stock drops to or below the configured minimum threshold.
 *
 * <p>Implements individual dispatch (AC1) and batch consolidation for >3 events
 * in the same store within a 60-second window (AC2).
 *
 * <p>Async best-effort: stock operations MUST NOT fail due to notification errors.
 *
 * <p>Story 8.1 — Alertes Stock Critique & Tendances de Ventes.
 */
@Component
public class StockAlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(StockAlertNotificationListener.class);

    private static final String COOLDOWN_TYPE_STOCK_ALERT = "STOCK_ALERT";
    private static final Duration STOCK_ALERT_COOLDOWN = Duration.ofHours(4);
    private static final int BATCH_THRESHOLD = 4;
    private static final Duration BATCH_WINDOW_DURATION = Duration.ofSeconds(60);

    private final NotificationPort notificationPort;
    private final WhatsAppPort whatsAppPort;
    private final NotificationCooldownRepository cooldownRepository;
    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final StoreRepository storeRepository;
    private final Clock clock;

    private final ConcurrentHashMap<String, BatchWindow> batchWindows = new ConcurrentHashMap<>();

    @Autowired
    public StockAlertNotificationListener(NotificationPort notificationPort,
                                          WhatsAppPort whatsAppPort,
                                          NotificationCooldownRepository cooldownRepository,
                                          TenantPreferencesRepository tenantPreferencesRepository,
                                          OwnerRecipientResolver ownerRecipientResolver,
                                          StoreRepository storeRepository) {
        this(notificationPort, whatsAppPort, cooldownRepository, tenantPreferencesRepository,
             ownerRecipientResolver, storeRepository, Clock.systemUTC());
    }

    /** Test-only constructor — injects a controllable {@link Clock} instead of the real one. */
    StockAlertNotificationListener(NotificationPort notificationPort,
                                   WhatsAppPort whatsAppPort,
                                   NotificationCooldownRepository cooldownRepository,
                                   TenantPreferencesRepository tenantPreferencesRepository,
                                   OwnerRecipientResolver ownerRecipientResolver,
                                   StoreRepository storeRepository,
                                   Clock clock) {
        this.clock = clock;
        this.notificationPort = notificationPort;
        this.whatsAppPort = whatsAppPort;
        this.cooldownRepository = cooldownRepository;
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.storeRepository = storeRepository;
    }

    @Async
    @EventListener
    public void onStockThresholdBreached(StockThresholdBreachedEvent event) {
        log.info("[STOCK-ALERT] Threshold breached: product={} store={} qty={} threshold={}",
                event.productId(), event.storeId(), event.currentQuantity(), event.threshold());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

            // AC1: check preferences
            TenantPreferences prefs = tenantPreferencesRepository.findByCurrentTenant().orElse(null);
            if (prefs == null || !prefs.stockAlertEnabled()) {
                log.debug("[STOCK-ALERT] Stock alerts disabled for tenant={}", event.tenantId());
                return;
            }

            StockAlertChannel channel = prefs.stockAlertChannel() != null
                    ? prefs.stockAlertChannel()
                    : StockAlertChannel.PUSH;

            String storeName = storeRepository.findById(event.storeId())
                    .map(s -> s.name())
                    .orElse("Boutique");

            String ownerPhone = resolveOwnerPhone(event.tenantId());

            // AC1: cooldown per product/store. Story 13.9: check + set in one atomic statement
            // so two concurrent threshold events for the same product cannot both notify.
            // Taken after the lookups: a failure there must not consume the 4h cooldown.
            if (!cooldownRepository.tryAcquireCooldown(
                    COOLDOWN_TYPE_STOCK_ALERT, event.productId(), event.storeId(), STOCK_ALERT_COOLDOWN)) {
                log.debug("[STOCK-ALERT] Cooldown active for product={} store={}", event.productId(), event.storeId());
                return;
            }

            // AC2: batch window tracking — Story 13.9: atomic per store key via compute();
            // expired windows are replaced here and evicted by evictExpiredWindows().
            String storeKey = event.tenantId() + ":" + event.storeId();
            Instant now = Instant.now(clock);
            List<StockThresholdBreachedEvent> batchEvents = new ArrayList<>();

            batchWindows.compute(storeKey, (k, window) -> {
                BatchWindow w = window;
                if (w == null || isExpired(w, now)) {
                    // Window expired or first event — fresh window
                    w = new BatchWindow();
                    w.windowStart = now;
                }
                w.events.add(event);
                if (w.events.size() >= BATCH_THRESHOLD && !w.batchDispatched) {
                    w.batchDispatched = true;
                    batchEvents.addAll(w.events);
                }
                return w;
            });

            if (!batchEvents.isEmpty()) {
                // This product's cooldown was acquired above; set the others in the batch.
                for (StockThresholdBreachedEvent e : batchEvents) {
                    if (!e.productId().equals(event.productId())) {
                        cooldownRepository.upsertCooldown(COOLDOWN_TYPE_STOCK_ALERT, e.productId(), e.storeId());
                    }
                }
                dispatchBatchNotification(batchEvents, storeName, ownerPhone, channel, event.tenantId());
            } else {
                dispatchIndividual(event, storeName, ownerPhone, channel);
            }

        } catch (Exception e) {
            log.warn("[STOCK-ALERT] Failed to notify for product={} store={}: {}",
                    event.productId(), event.storeId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Story 13.9 — periodic eviction of batch windows older than the 60 s batch window, so
     * the map does not keep one entry per (tenant, store) forever. In-memory only: runs on
     * every instance (no ShedLock), each instance owns its own map.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredWindows() {
        Instant now = Instant.now(clock);
        for (String key : batchWindows.keySet()) {
            batchWindows.computeIfPresent(key, (k, w) -> isExpired(w, now) ? null : w);
        }
    }

    /** Test hook — number of open batch windows. */
    int openWindowCount() {
        return batchWindows.size();
    }

    private static boolean isExpired(BatchWindow w, Instant now) {
        return w.windowStart == null || now.isAfter(w.windowStart.plus(BATCH_WINDOW_DURATION));
    }

    private void dispatchIndividual(StockThresholdBreachedEvent event,
                                    String storeName, String ownerPhone,
                                    StockAlertChannel channel) {
        String body = String.format("%s : %d unité(s) restante(s) (seuil : %d) — %s",
                event.productName(), event.currentQuantity(), event.threshold(), storeName);

        NotificationPayload payload = NotificationPayload.of(
                "STOCK_ALERT",
                "⚠\uFE0F Stock bas",
                body,
                "/products/" + event.productId() + "/edit",
                Map.of(
                        "productId", event.productId().toString(),
                        "storeId", event.storeId().toString(),
                        "currentQty", String.valueOf(event.currentQuantity()),
                        "threshold", String.valueOf(event.threshold())
                )
        );

        Exception pushError = null;
        Exception waError = null;

        if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
            try {
                notificationPort.notifyOwners(event.tenantId(), payload);
            } catch (Exception e) {
                pushError = e;
            }
        }

        if ((channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH)
                && ownerPhone != null) {
            try {
                whatsAppPort.sendReport(ownerPhone, body);
            } catch (Exception e) {
                waError = e;
            }
        }

        if (pushError != null && waError != null) {
            log.warn("[STOCK-ALERT] All channels failed for product={} store={}: push={}, wa={}",
                    event.productId(), event.storeId(), pushError.getMessage(), waError.getMessage());
        } else if (pushError != null) {
            log.warn("[STOCK-ALERT] Push failed for product={} store={}: {}",
                    event.productId(), event.storeId(), pushError.getMessage());
        } else if (waError != null) {
            log.warn("[STOCK-ALERT] WhatsApp failed for product={} store={}: {}",
                    event.productId(), event.storeId(), waError.getMessage());
        }
    }

    private void dispatchBatchNotification(List<StockThresholdBreachedEvent> events,
                                           String storeName, String ownerPhone,
                                           StockAlertChannel channel, String tenantId) {
        int count = events.size();
        String body = String.format("%d produits en stock bas — %s. Consultez l'onglet Stock.", count, storeName);

        NotificationPayload payload = NotificationPayload.of(
                "STOCK_ALERT_BATCH",
                "⚠\uFE0F " + count + " produits en stock bas",
                body,
                "/stock/overview",
                Map.of(
                        "storeId", events.get(0).storeId().toString(),
                        "count", String.valueOf(count)
                )
        );

        Exception pushError = null;
        Exception waError = null;

        if (channel == StockAlertChannel.PUSH || channel == StockAlertChannel.BOTH) {
            try {
                notificationPort.notifyOwners(tenantId, payload);
            } catch (Exception e) {
                pushError = e;
            }
        }

        if ((channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH)
                && ownerPhone != null) {
            try {
                StringBuilder waMessage = new StringBuilder();
                waMessage.append("⚠\uFE0F ").append(count).append(" produits en stock bas — ").append(storeName).append("\n\n");
                for (StockThresholdBreachedEvent e : events) {
                    waMessage.append("• ").append(e.productName()).append(" : ")
                            .append(e.currentQuantity()).append(" unité(s) (seuil : ")
                            .append(e.threshold()).append(")\n");
                }
                whatsAppPort.sendReport(ownerPhone, waMessage.toString());
            } catch (Exception e) {
                waError = e;
            }
        }

        if (pushError != null && waError != null) {
            log.warn("[STOCK-ALERT] All channels failed for batch store={}: push={}, wa={}",
                    events.get(0).storeId(), pushError.getMessage(), waError.getMessage());
        }
    }

    private String resolveOwnerPhone(String tenantId) {
        return ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)
                .orElseGet(() -> {
                    log.debug("[STOCK-ALERT] No owner phone found for tenant={}", tenantId);
                    return null;
                });
    }

    // ── Batch window inner class ─────────────────────────────────────────────

    static class BatchWindow {
        Instant windowStart;
        final List<StockThresholdBreachedEvent> events = new ArrayList<>();
        boolean batchDispatched;
    }
}
