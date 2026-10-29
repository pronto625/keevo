package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.event.catalog.transfer.TransferCreatedEvent;
import com.keevo.messaging.notification.application.strategy.TransferNotificationRecipientStrategy;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.DeliveryFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TransferCreatedNotificationListener — notifies OWNER users + EMPLOYEE users
 * assigned to the destination store when a stock transfer is created (Step 1).
 *
 * <p>GoF Patterns:
 * <ul>
 *   <li>Observer — reacts to {@link TransferCreatedEvent} without coupling the
 *       transfer domain to this notification logic.</li>
 *   <li>Strategy — recipient selection is fully encapsulated in
 *       {@link TransferNotificationRecipientStrategy}; swap or extend strategies
 *       without modifying this listener (OCP).</li>
 * </ul>
 *
 * <p>Async best-effort — transfer operations MUST NOT fail due to notification errors.
 *
 * <p>Story 12.4: durable module listener (async, after commit, publication registry); the tenant is
 * bound from the event by the module-listener interceptor (the recipient strategy reads tenant-bound
 * repositories). {@link DeliverOnce}: a replay never pushes the transfer twice. A provider failure on the
 * send is rethrown as {@link DeliveryFailedException} (ruling R2) so the publication is retried; recipient
 * resolution failures / no recipient stay best-effort (logged).
 *
 * Story HF-2 AC2, AC7.
 */
@Component
public class TransferCreatedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TransferCreatedNotificationListener.class);

    private final TransferNotificationRecipientStrategy recipientStrategy;
    private final PushNotifier pushNotifier;

    public TransferCreatedNotificationListener(
            TransferNotificationRecipientStrategy recipientStrategy,
            PushNotifier pushNotifier) {
        this.recipientStrategy = recipientStrategy;
        this.pushNotifier = pushNotifier;
    }

    /**
     * Fires after a transfer record is committed (Step 1, status IN_TRANSIT).
     * AC2: recipients = all OWNER users + all EMPLOYEE users of destination store.
     * AC7: deep link is router-valid (/stock/transfers).
     */
    @ApplicationModuleListener
    @DeliverOnce
    public void onTransferCreated(TransferCreatedEvent event) {
        log.info("[TRANSFER-CREATED] transfer={} dest={} tenant={}",
                event.transferId(), event.destinationStoreId(), event.tenantId());
        try {
            List<UUID> recipients = recipientStrategy.resolveRecipients(event);
            if (recipients.isEmpty()) {
                log.debug("[TRANSFER-CREATED] No recipients resolved for tenant={}", event.tenantId());
                return;
            }

            NotificationPayload payload = NotificationPayload.of(
                    "STOCK_TRANSFER_CREATED",
                    "📦 Nouveau transfert de stock",
                    "Un transfert de " + event.quantity() + " article(s) est en transit vers votre boutique.",
                    "/stock/transfers",   // AC7: router-valid deep link
                    Map.of(
                            "transferId", event.transferId().toString(),
                            "destinationStoreId", event.destinationStoreId().toString()
                    )
            );

            try {
                pushNotifier.notifyUsers(event.tenantId(), recipients, payload);
            } catch (RuntimeException e) {
                // Story 12.4 (R2): rethrown — the publication stays incomplete and is retried
                log.warn("[TRANSFER-CREATED] Push failed for transfer={}: {} — will be retried",
                        event.transferId(), e.getMessage());
                throw new DeliveryFailedException("transfer push send failed", e);
            }

        } catch (DeliveryFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[TRANSFER-CREATED] Notification failed for transfer={}: {}",
                    event.transferId(), e.getMessage());
        }
    }
}
