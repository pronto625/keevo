package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.product.domain.event.CsvImportCompletedEvent;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DraftProductNotificationListener — handles CSV import completion events and dispatches
 * a consolidated notification via {@link NotificationPort}.
 *
 * <p>The individual draft-product notification (one per product) has been intentionally
 * removed. The owner is now notified at the more meaningful moment: when a sale containing
 * draft products is submitted as PENDING_VALIDATION (see
 * {@link SaleDraftValidatedNotificationListener#onSalePendingValidation}).
 *
 * <p>Story 2.4 — AC9 CSV batch notification.
 */
@Component
public class DraftProductNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DraftProductNotificationListener.class);

    private final NotificationPort notificationPort;

    public DraftProductNotificationListener(NotificationPort notificationPort) {
        this.notificationPort = notificationPort;
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

