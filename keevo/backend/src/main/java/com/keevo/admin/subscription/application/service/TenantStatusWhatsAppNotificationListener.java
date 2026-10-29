package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.DeliveryFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * TenantStatusWhatsAppNotificationListener — tells the tenant owner on WhatsApp that the super admin
 * suspended or reactivated the account (Story 9-2). Same contract as
 * {@code PlanWhatsAppNotificationListener}: AFTER_COMMIT, recipient from {@link OwnerRecipientResolver}
 * only, the status change is already committed.
 *
 * <p>Story 12.4: synchronous after commit (admin → admin), recorded by the publication registry;
 * {@link DeliverOnce} (a replay never sends twice). A provider failure on the send is rethrown as
 * {@link DeliveryFailedException} (ruling R2): the publication stays incomplete and is retried. No owner
 * phone / failed owner lookup → warn and skip.
 */
@Component
public class TenantStatusWhatsAppNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusWhatsAppNotificationListener.class);

    /** Owner-facing texts of the account status changes (moved from subscription's PlanWhatsAppMessages, 12.2d). */
    static final String SUSPENDED = "Votre compte Keevo est suspendu. Contactez le support Keevo.";
    static final String REACTIVATED = "Votre compte Keevo est réactivé.";

    private final WhatsAppSender whatsAppSender;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public TenantStatusWhatsAppNotificationListener(WhatsAppSender whatsAppSender,
                                                    OwnerRecipientResolver ownerRecipientResolver) {
        this.whatsAppSender = whatsAppSender;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onSuspended(TenantSuspendedEvent event) {
        send(event.tenantId(), event.schemaName(), "suspension", SUSPENDED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onReactivated(TenantReactivatedEvent event) {
        send(event.tenantId(), event.schemaName(), "reactivation", REACTIVATED);
    }

    private void send(UUID tenantId, String schemaName, String kind, String text) {
        Optional<String> phone;
        try {
            phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(schemaName);
        } catch (RuntimeException e) {
            log.warn("Tenant {} WhatsApp skipped for tenant {}: owner lookup failed: {}: {}",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (phone.isEmpty()) {
            log.warn("Tenant {} WhatsApp skipped for tenant {}: no owner WhatsApp phone", kind, tenantId);
            return;
        }
        try {
            whatsAppSender.sendReport(phone.get(), text);
        } catch (RuntimeException e) {
            // Story 12.4 (R2): rethrown — the publication stays incomplete and is retried
            log.warn("Tenant {} WhatsApp failed for tenant {}: {}: {} — will be retried",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
            throw new DeliveryFailedException("Tenant " + kind + " WhatsApp send failed", e);
        }
        log.info("Tenant {} WhatsApp sent for tenant {}", kind, tenantId);
    }
}
