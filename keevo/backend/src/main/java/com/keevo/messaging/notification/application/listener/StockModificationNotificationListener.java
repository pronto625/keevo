package com.keevo.messaging.notification.application.listener;

import com.keevo.inventory.stock.domain.entity.MovementType;
import com.keevo.inventory.stock.domain.event.StockAdjustedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * StockModificationNotificationListener — notifies the owner when an employee
 * manually modifies stock (STOCK_ENTRY or ADJUSTMENT).
 *
 * <p>Excludes: SALE decrements, transfers, OWNER-triggered operations.
 *
 * <p>Batching (AC5/D4): events for the same (tenant, actor) are accumulated
 * silently; {@link #flushExpiredWindows()} — a periodic sweep, not a per-event
 * dispatch — sends exactly ONE notification per window when it expires: a
 * single notification if only one event occurred, or one consolidated recap
 * if several did.
 *
 * <p>GoF: Observer — async {@code @EventListener}, best-effort (no rollback).
 *
 * <p>Story 14.10.
 */
@Component
public class StockModificationNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(StockModificationNotificationListener.class);

    private static final String TYPE_EMPLOYEE_STOCK_MODIFIED = "EMPLOYEE_STOCK_MODIFIED";
    private static final String TYPE_EMPLOYEE_STOCK_MODIFIED_BATCH = "EMPLOYEE_STOCK_MODIFIED_BATCH";
    private static final long DEFAULT_WINDOW_MIN = 5;

    private final NotificationPort notificationPort;
    private final WhatsAppPort whatsAppPort;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final long windowMin;
    private final Clock clock;

    private final ConcurrentHashMap<String, BatchWindow> batchWindows = new ConcurrentHashMap<>();

    @Autowired
    public StockModificationNotificationListener(
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort,
            OwnerRecipientResolver ownerRecipientResolver,
            @Value("${keevo.notification.employee-action-window-min:" + DEFAULT_WINDOW_MIN + "}")
            long windowMin) {
        this(notificationPort, whatsAppPort, ownerRecipientResolver, windowMin, Clock.systemUTC());
    }

    /** Test-only constructor — injects a controllable {@link Clock} instead of the real one. */
    StockModificationNotificationListener(
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort,
            OwnerRecipientResolver ownerRecipientResolver,
            long windowMin,
            Clock clock) {
        this.notificationPort = notificationPort;
        this.whatsAppPort = whatsAppPort;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.windowMin = windowMin;
        this.clock = clock;
    }

    @Async
    @EventListener
    public void onStockAdjusted(StockAdjustedEvent event) {
        // Skip if not EMPLOYEE
        if (!"EMPLOYEE".equals(event.actorRole())) {
            log.debug("[STOCK-NOTIF] Skipping — actor is not EMPLOYEE (role={})", event.actorRole());
            return;
        }
        // Skip SALE and TRANSFER movements (D2)
        if (event.movementType() != MovementType.STOCK_ENTRY
                && event.movementType() != MovementType.ADJUSTMENT) {
            log.debug("[STOCK-NOTIF] Skipping — movementType={} not in scope", event.movementType());
            return;
        }
        try {
            String key = batchKey(event.tenantId(), event.actorId());
            // Story 13.9: accumulate atomically. If the flush removed the key meanwhile, this
            // starts a fresh window (never an orphan one). An expired window the sweep has not
            // flushed yet keeps collecting: the next sweep sends everything, nothing is dropped.
            batchWindows.compute(key, (k, window) -> {
                BatchWindow w = window != null ? window : new BatchWindow();
                if (w.windowStart == null) {
                    w.windowStart = Instant.now(clock);
                }
                w.events.add(event);
                return w;
            });
        } catch (Exception e) {
            log.warn("[STOCK-NOTIF] Failed to accumulate event for stock modification by {}: {}",
                    event.actorId(), e.getMessage());
        }
    }

    /**
     * AC5/D4 — periodic sweep: dispatch exactly ONE notification per window once it
     * has expired (single if 1 event, consolidated recap if N≥2).
     */
    @Scheduled(fixedDelay = 30_000)
    public void flushExpiredWindows() {
        Instant now = Instant.now(clock);
        Duration window = Duration.ofMinutes(windowMin);
        for (String key : batchWindows.keySet()) {
            // Story 13.9: an expired window is removed atomically together with the extraction
            // of its events (the map no longer keeps one entry per actor forever); an event
            // arriving right after lands in a fresh window via compute().
            List<StockAdjustedEvent> toSend = new ArrayList<>();
            batchWindows.computeIfPresent(key, (k, w) -> {
                if (w.windowStart == null || now.isAfter(w.windowStart.plus(window))) {
                    toSend.addAll(w.events);
                    return null;
                }
                return w;
            });
            if (!toSend.isEmpty()) {
                try {
                    if (toSend.size() == 1) {
                        dispatchSingle(toSend.get(0));
                    } else {
                        dispatchBatch(toSend);
                    }
                } catch (Exception e) {
                    log.warn("[STOCK-NOTIF] Failed to flush window for key={}: {}", key, e.getMessage());
                }
            }
        }
    }

    /** Test hook — number of open batch windows (Story 13.9 eviction). */
    int openWindowCount() {
        return batchWindows.size();
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private void dispatchSingle(StockAdjustedEvent event) {
        String productName = productLabel(event);
        String storeLabel = event.storeName() != null ? " (" + event.storeName() + ")" : "";
        String body;
        if (event.movementType() == MovementType.STOCK_ENTRY) {
            body = event.actorName() + " a ajouté " + event.quantityChange()
                    + " unité(s) à « " + productName + " »" + storeLabel + ".";
        } else {
            body = event.actorName() + " a ajusté « " + productName + " »"
                    + storeLabel + " : " + event.quantityBefore()
                    + " → " + event.quantityAfter() + ".";
        }

        NotificationPayload payload = NotificationPayload.of(
                TYPE_EMPLOYEE_STOCK_MODIFIED,
                "📦 Stock modifié par " + event.actorName(),
                body,
                "/products/" + event.productId() + "/edit",  // J16: existing app route
                Map.of("productId", event.productId().toString(),
                       "productName", productName,
                       "actorId", event.actorId().toString(),
                       "storeName", event.storeName() != null ? event.storeName() : "",
                       "movementType", event.movementType().name(),
                       "tenantId", event.tenantId())
        );

        sendNotification(event.tenantId(), payload, body);
    }

    private void dispatchBatch(List<StockAdjustedEvent> events) {
        if (events.isEmpty()) return;
        StockAdjustedEvent first = events.get(0);
        int count = events.size();
        String storeLabel = first.storeName() != null ? " (" + first.storeName() + ")" : "";
        String body = first.actorName() + " a effectué " + count
                + " modification(s) de stock" + storeLabel + ".";

        NotificationPayload payload = NotificationPayload.of(
                TYPE_EMPLOYEE_STOCK_MODIFIED_BATCH,
                "📦 " + count + " modifications de stock par " + first.actorName(),
                body,
                "/stock/overview",  // J16: existing app route
                Map.of("count", String.valueOf(count),
                       "actorId", first.actorId().toString(),
                       "storeName", first.storeName() != null ? first.storeName() : "",
                       "tenantId", first.tenantId())
        );

        sendNotification(first.tenantId(), payload, body);
    }

    private void sendNotification(String tenantId, NotificationPayload payload, String whatsAppBody) {
        Exception pushError = null;
        Exception waError = null;

        try {
            notificationPort.notifyOwners(tenantId, payload);
        } catch (Exception e) {
            pushError = e;
        }

        String ownerPhone = resolveOwnerPhone(tenantId);
        if (ownerPhone != null) {
            try {
                whatsAppPort.sendReport(ownerPhone, whatsAppBody);
            } catch (Exception e) {
                waError = e;
            }
        } else {
            log.debug("[STOCK-NOTIF] No owner phone found for tenant={}, skipping WhatsApp", tenantId);
        }

        if (pushError != null && waError != null) {
            log.warn("[STOCK-NOTIF] Both channels failed for tenant={}: push={}, wa={}",
                    tenantId, pushError.getMessage(), waError.getMessage());
        } else if (pushError != null) {
            log.warn("[STOCK-NOTIF] Push failed for tenant={}: {}", tenantId, pushError.getMessage());
        } else if (waError != null) {
            log.warn("[STOCK-NOTIF] WhatsApp failed for tenant={}: {}", tenantId, waError.getMessage());
        }
    }

    private String resolveOwnerPhone(String tenantId) {
        return ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantId)
                .orElse(null);
    }

    /** Product name resolved on the event by {@code StockOperationService}; falls back to a truncated id. */
    private String productLabel(StockAdjustedEvent event) {
        if (event.productName() != null && !event.productName().isBlank()) {
            return event.productName();
        }
        return event.productId().toString().substring(0, 8) + "…";
    }

    private static String batchKey(String tenantId, UUID actorId) {
        return tenantId + ":" + actorId;
    }

    // ── Batch window holder ────────────────────────────────────────────

    private static class BatchWindow {
        Instant windowStart;
        final List<StockAdjustedEvent> events = new ArrayList<>();
    }
}
