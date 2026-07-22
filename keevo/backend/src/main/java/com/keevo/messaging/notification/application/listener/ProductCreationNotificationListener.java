package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.identity.auth.domain.port.out.UserRepository;
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
 * ProductCreationNotificationListener — notifies the owner when an employee
 * creates an ACTIVE product, or completes a CSV import.
 *
 * <p>Story 14.10: extends the former {@code DraftProductNotificationListener}
 * with {@link ProductCreatedEvent} handling and consolidated batching.
 *
 * <p>Batching (AC5/D4): events for the same (tenant, actor) are accumulated
 * silently; {@link #flushExpiredWindows()} — a periodic sweep, not a per-event
 * dispatch — sends exactly ONE notification per window when it expires: a
 * single notification if only one event occurred, or one consolidated recap
 * if several did.
 *
 * <p>GoF: Observer — async {@code @EventListener}, best-effort (no rollback).
 */
@Component
public class ProductCreationNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductCreationNotificationListener.class);

    private static final String TYPE_EMPLOYEE_PRODUCT_CREATED = "EMPLOYEE_PRODUCT_CREATED";
    private static final String TYPE_EMPLOYEE_PRODUCT_CREATED_BATCH = "EMPLOYEE_PRODUCT_CREATED_BATCH";
    private static final long DEFAULT_WINDOW_MIN = 5;

    private final NotificationPort notificationPort;
    private final WhatsAppPort whatsAppPort;
    private final UserRepository userRepository;
    private final long windowMin;
    private final Clock clock;

    /** Per (tenantId:actorId) → BatchWindow. */
    private final ConcurrentHashMap<String, BatchWindow> batchWindows = new ConcurrentHashMap<>();

    @Autowired
    public ProductCreationNotificationListener(
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort,
            UserRepository userRepository,
            @Value("${keevo.notification.employee-action-window-min:" + DEFAULT_WINDOW_MIN + "}")
            long windowMin) {
        this(notificationPort, whatsAppPort, userRepository, windowMin, Clock.systemUTC());
    }

    /** Test-only constructor — injects a controllable {@link Clock} instead of the real one. */
    ProductCreationNotificationListener(
            NotificationPort notificationPort,
            WhatsAppPort whatsAppPort,
            UserRepository userRepository,
            long windowMin,
            Clock clock) {
        this.notificationPort = notificationPort;
        this.whatsAppPort = whatsAppPort;
        this.userRepository = userRepository;
        this.windowMin = windowMin;
        this.clock = clock;
    }

    // ── ACTIVE product (Story 14.10) ────────────────────────────────────

    @Async
    @EventListener
    public void onProductCreated(ProductCreatedEvent event) {
        if (!"EMPLOYEE".equals(event.actorRole())) {
            log.debug("[PRODUCT-NOTIF] Skipping — actor is not EMPLOYEE (role={})", event.actorRole());
            return;
        }
        try {
            String key = batchKey(event.tenantId(), event.actorId());
            BatchWindow window = batchWindows.computeIfAbsent(key, k -> new BatchWindow());

            synchronized (window) {
                Instant now = Instant.now(clock);
                boolean expired = window.windowStart != null
                        && now.isAfter(window.windowStart.plus(Duration.ofMinutes(windowMin)));

                if (expired || window.windowStart == null) {
                    window.events.clear();
                    window.windowStart = now;
                }
                window.events.add(event);
            }
        } catch (Exception e) {
            log.warn("[PRODUCT-NOTIF] Failed to accumulate event for product creation by {}: {}",
                    event.actorId(), e.getMessage());
        }
    }

    /**
     * AC5/D4 — periodic sweep: dispatch exactly ONE notification per window once it
     * has expired (single if 1 event, consolidated recap if N≥2). Never dispatches
     * from the event handler itself, so a burst of employee actions never produces
     * more than one notification per window.
     */
    @Scheduled(fixedDelay = 30_000)
    public void flushExpiredWindows() {
        Instant now = Instant.now(clock);
        for (Map.Entry<String, BatchWindow> entry : batchWindows.entrySet()) {
            BatchWindow window = entry.getValue();
            List<ProductCreatedEvent> toSend = null;
            synchronized (window) {
                if (window.windowStart != null
                        && now.isAfter(window.windowStart.plus(Duration.ofMinutes(windowMin)))
                        && !window.events.isEmpty()) {
                    toSend = new ArrayList<>(window.events);
                    window.events.clear();
                    window.windowStart = null;
                }
            }
            if (toSend != null) {
                try {
                    if (toSend.size() == 1) {
                        dispatchSingle(toSend.get(0));
                    } else {
                        dispatchBatch(toSend);
                    }
                } catch (Exception e) {
                    log.warn("[PRODUCT-NOTIF] Failed to flush window for key={}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
    }

    // ── CSV import (Story 2.4 AC9 — preserved) ─────────────────────────

    @Async
    @EventListener
    public void onCsvImportCompleted(CsvImportCompletedEvent event) {
        log.info("[CSV] Import completed: {} product(s) by {} in tenant {}",
                event.importedCount(), event.actorId(), event.tenantId());

        try {
            String body = event.actorName() + " a importé " + event.importedCount()
                    + " produit(s) en brouillon via CSV — Validez-les dans votre catalogue";

            notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                    "CSV_IMPORT_DRAFTS_PENDING",
                    "🔶 Import CSV — produits en attente",
                    body,
                    "/products",
                    Map.of("count", String.valueOf(event.importedCount()))
            ));
        } catch (Exception e) {
            log.warn("[NOTIFICATION] Failed to notify owners for CSV import by {}: {}",
                    event.actorId(), e.getMessage());
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private void dispatchSingle(ProductCreatedEvent event) {
        String storeLabel = event.storeName() != null ? " (" + event.storeName() + ")" : "";
        String body = event.actorName() + " a ajouté « " + event.productName()
                + " » au catalogue" + storeLabel + ".";

        NotificationPayload payload = NotificationPayload.of(
                TYPE_EMPLOYEE_PRODUCT_CREATED,
                "🛍️ Produit créé par " + event.actorName(),
                body,
                "/products/" + event.productId() + "/edit",
                Map.of("productId", event.productId().toString(),
                       "productName", event.productName(),
                       "actorId", event.actorId().toString(),
                       "storeName", event.storeName() != null ? event.storeName() : "",
                       "tenantId", event.tenantId())
        );

        sendNotification(event.tenantId(), payload, body);
    }

    private void dispatchBatch(List<ProductCreatedEvent> events) {
        if (events.isEmpty()) return;
        ProductCreatedEvent first = events.get(0);
        int count = events.size();
        String storeLabel = first.storeName() != null ? " (" + first.storeName() + ")" : "";
        String body = first.actorName() + " a créé " + count
                + " produits dans le catalogue" + storeLabel + ".";

        NotificationPayload payload = NotificationPayload.of(
                TYPE_EMPLOYEE_PRODUCT_CREATED_BATCH,
                "🛍️ " + count + " produits créés par " + first.actorName(),
                body,
                "/products",
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
            log.debug("[PRODUCT-NOTIF] No owner phone found for tenant={}, skipping WhatsApp", tenantId);
        }

        if (pushError != null && waError != null) {
            log.warn("[PRODUCT-NOTIF] Both channels failed for tenant={}: push={}, wa={}",
                    tenantId, pushError.getMessage(), waError.getMessage());
        } else if (pushError != null) {
            log.warn("[PRODUCT-NOTIF] Push failed for tenant={}: {}", tenantId, pushError.getMessage());
        } else if (waError != null) {
            log.warn("[PRODUCT-NOTIF] WhatsApp failed for tenant={}: {}", tenantId, waError.getMessage());
        }
    }

    private String resolveOwnerPhone(String tenantId) {
        return userRepository.findOwnerByTenantSchemaName(tenantId)
                .map(u -> u.getPhoneNumber())
                .orElse(null);
    }

    private static String batchKey(String tenantId, UUID actorId) {
        return tenantId + ":" + actorId;
    }

    // ── Batch window holder ────────────────────────────────────────────

    private static class BatchWindow {
        Instant windowStart;
        final List<ProductCreatedEvent> events = new ArrayList<>();
    }
}
