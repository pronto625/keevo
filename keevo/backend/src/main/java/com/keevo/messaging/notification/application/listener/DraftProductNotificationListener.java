package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.catalog.product.domain.event.ProductCreatedProgressivelyEvent;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DraftProductNotificationListener — handles domain events related to draft products
 * and CSV import completion, then dispatches notifications via {@link NotificationPort}.
 *
 * <p>Async: each notification is dispatched on a separate thread-pool thread.
 * ({@code @EnableAsync} required — see {@link com.keevo.shared.infrastructure.config.AsyncConfig}).
 *
 * <p>Owner self-notification: if the OWNER creates a draft, a notification IS sent as a
 * reminder to validate the product. This is intentional ("Sérénité par défaut" — both
 * owner and employee drafts trigger the validation reminder).
 *
 * <p>Story 2.4 — AC9 (draft notification), AC9 CSV batch notification.
 */
@Component
public class DraftProductNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DraftProductNotificationListener.class);

    private final NotificationPort notificationPort;

    public DraftProductNotificationListener(NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
    }

    /**
     * AC9: Notify the owner when a DRAFT product is created (by self or by employee).
     * Owner self-notification is intentional — acts as a reminder to validate.
     */
    @Async
    @EventListener
    public void onDraftCreated(ProductCreatedProgressivelyEvent event) {
        log.info("[DRAFT] New draft '{}' by {} (role={}) in tenant {}",
                event.productName(), event.actorId(), event.actorRole(), event.tenantId());

        try {
            String body = event.actorName() + " a enregistré un nouveau produit en brouillon : '"
                    + event.productName() + "' — Validez-le pour l'activer dans votre catalogue";

            notificationPort.notifyOwners(event.tenantId(), NotificationPayload.of(
                    "DRAFT_PRODUCT_PENDING_VALIDATION",
                    "\uD83D\uDD36 Produit en attente de validation",
                    body,
                    "/products/" + event.productId() + "/edit",
                    Map.of(
                            "productId",   event.productId().toString(),
                            "productName", event.productName(),
                            "tenantId",    event.tenantId()
                    )
            ));
        } catch (Exception e) {
            // Best-effort — draft creation must never fail due to notification error
            log.warn("[NOTIFICATION] Failed to notify owners for draft '{}': {}",
                    event.productName(), e.getMessage());
        }
    }

    /**
     * AC9: Notify the owner when a CSV bulk import completes — ONE consolidated notification
     * instead of N individual ones (prevents notification spam).
     */
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
                    "\uD83D\uDD36 Import CSV — produits en attente",
                    body,
                    "/products",
                    Map.of("count", String.valueOf(event.importedCount()))
            ));
        } catch (Exception e) {
            log.warn("[NOTIFICATION] Failed to notify owners for CSV import by {}: {}",
                    event.actorId(), e.getMessage());
        }
    }
}

