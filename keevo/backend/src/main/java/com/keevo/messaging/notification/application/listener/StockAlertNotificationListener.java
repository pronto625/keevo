package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.api.TenantPreferencesView;
import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.DeliveryFailedException;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.shared.module.ModuleScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
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
 * <p>Story 12.6a: durable module listener (async, <b>after commit</b>, registry publication; tenant bound from
 * the event by the module-listener interceptor) — no alert for a stock change whose transaction rolled back.
 * {@code @DeliverOnce}: a registry replay never re-sends (the 4 h cooldown would also skip it).
 *
 * <p><b>Individual alert</b> (sent from the handler): a provider failure is rethrown as
 * {@link DeliveryFailedException} once both channels were attempted (12.4 ruling R2) — the publication stays
 * incomplete and is retried; the listener transaction rolls back, releasing the cooldown (a partial failure may
 * repeat the channel that succeeded).
 *
 * <p><b>Batched alerts are not durable</b> (pre-existing batching, unchanged): the batch window lives in
 * memory. The publication of an event that only joined a window completes (and its receipt is recorded) before
 * the batch is sent, so a restart loses the batched alerts not yet sent; a batch send failure is only logged.
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

    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;
    private final NotificationCooldownRepository cooldownRepository;
    private final TenantPreferencesQueryPort preferencesQuery;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final StoreQueryPort storeQuery;
    private final Clock clock;

    private final ConcurrentHashMap<String, BatchWindow> batchWindows = new ConcurrentHashMap<>();

    @Autowired
    public StockAlertNotificationListener(PushNotifier pushNotifier,
                                          WhatsAppSender whatsAppSender,
                                          NotificationCooldownRepository cooldownRepository,
                                          TenantPreferencesQueryPort preferencesQuery,
                                          OwnerRecipientResolver ownerRecipientResolver,
                                          StoreQueryPort storeQuery) {
        this(pushNotifier, whatsAppSender, cooldownRepository, preferencesQuery,
             ownerRecipientResolver, storeQuery, Clock.systemUTC());
    }

    /** Test-only constructor — injects a controllable {@link Clock} instead of the real one. */
    StockAlertNotificationListener(PushNotifier pushNotifier,
                                   WhatsAppSender whatsAppSender,
                                   NotificationCooldownRepository cooldownRepository,
                                   TenantPreferencesQueryPort preferencesQuery,
                                   OwnerRecipientResolver ownerRecipientResolver,
                                   StoreQueryPort storeQuery,
                                   Clock clock) {
        this.clock = clock;
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
        this.cooldownRepository = cooldownRepository;
        this.preferencesQuery = preferencesQuery;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.storeQuery = storeQuery;
    }

    @ModuleScoped("alerts") // Story 13.10a: owner alerts are a paid module (plan §6.1)
    @ApplicationModuleListener
    @DeliverOnce
    public void onStockThresholdBreached(StockThresholdBreachedEvent event) {
        log.info("[STOCK-ALERT] Threshold breached: product={} store={} qty={} threshold={}",
                event.productId(), event.storeId(), event.currentQuantity(), event.threshold());
        try {
            // AC1: check preferences
            TenantPreferencesView prefs = preferencesQuery.find(event.tenantId()).orElse(null);
            if (prefs == null || !prefs.stockAlertEnabled()) {
                log.debug("[STOCK-ALERT] Stock alerts disabled for tenant={}", event.tenantId());
                return;
            }

            StockAlertChannel channel = prefs.stockAlertChannel() != null
                    ? prefs.stockAlertChannel()
                    : StockAlertChannel.PUSH;

            String storeName = storeQuery.findById(event.tenantId(), event.storeId())
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

        } catch (DeliveryFailedException e) {
            throw e; // immediate send failed: publication stays incomplete, retried (12.4 R2)
        } catch (Exception e) {
            log.warn("[STOCK-ALERT] Failed to notify for product={} store={}: {}",
                    event.productId(), event.storeId(), e.getMessage());
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
                pushNotifier.notifyOwners(event.tenantId(), payload);
            } catch (Exception e) {
                pushError = e;
            }
        }

        if ((channel == StockAlertChannel.WHATSAPP || channel == StockAlertChannel.BOTH)
                && ownerPhone != null) {
            try {
                whatsAppSender.sendReport(ownerPhone, body);
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
        // 12.4 R2 (12.6a): both channels were attempted; rethrow the first failure (the other suppressed) so
        // the publication stays incomplete and the receipt is released. The listener transaction rolls back,
        // so the cooldown taken above is released too and the retry can send.
        if (pushError != null || waError != null) {
            DeliveryFailedException failure = new DeliveryFailedException(
                    pushError != null ? "push send failed" : "WhatsApp send failed",
                    pushError != null ? pushError : waError);
            if (pushError != null && waError != null) {
                failure.addSuppressed(new DeliveryFailedException("WhatsApp send failed", waError));
            }
            throw failure;
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
                pushNotifier.notifyOwners(tenantId, payload);
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
                whatsAppSender.sendReport(ownerPhone, waMessage.toString());
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
