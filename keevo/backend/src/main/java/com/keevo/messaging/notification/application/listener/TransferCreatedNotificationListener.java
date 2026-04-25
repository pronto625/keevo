package com.keevo.messaging.notification.application.listener;

import com.keevo.catalog.stock.domain.event.TransferCreatedEvent;
import com.keevo.messaging.notification.application.strategy.TransferNotificationRecipientStrategy;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

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
 * Story HF-2 AC2, AC7.
 */
@Component
public class TransferCreatedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TransferCreatedNotificationListener.class);

    private final TransferNotificationRecipientStrategy recipientStrategy;
    private final NotificationPort notificationPort;

    public TransferCreatedNotificationListener(
            TransferNotificationRecipientStrategy recipientStrategy,
            NotificationPort notificationPort) {
        this.recipientStrategy = recipientStrategy;
        this.notificationPort = notificationPort;
    }

    /**
     * Fires after a transfer record is committed (Step 1, status IN_TRANSIT).
     * AC2: recipients = all OWNER users + all EMPLOYEE users of destination store.
     * AC7: deep link is router-valid (/stock/transfers).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCreated(TransferCreatedEvent event) {
        log.info("[TRANSFER-CREATED] transfer={} dest={} tenant={}",
                event.transferId(), event.destinationStoreId(), event.tenantId());
        try {
            TenantContext.setCurrentTenant(event.tenantId());

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

            notificationPort.notifyUsers(event.tenantId(), recipients, payload);

        } catch (Exception e) {
            log.warn("[TRANSFER-CREATED] Notification failed for transfer={}: {}",
                    event.transferId(), e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }
}
