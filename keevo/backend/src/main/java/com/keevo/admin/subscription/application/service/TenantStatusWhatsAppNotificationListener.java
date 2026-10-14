package com.keevo.admin.subscription.application.service;

import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.subscription.plan.application.service.PlanWhatsAppMessages;
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
 * only, every failure logged and swallowed (the status change is already committed).
 */
@Component
public class TenantStatusWhatsAppNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusWhatsAppNotificationListener.class);

    private final WhatsAppPort whatsAppPort;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public TenantStatusWhatsAppNotificationListener(WhatsAppPort whatsAppPort,
                                                    OwnerRecipientResolver ownerRecipientResolver) {
        this.whatsAppPort = whatsAppPort;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSuspended(TenantSuspendedEvent event) {
        send(event.tenantId(), event.schemaName(), "suspension", PlanWhatsAppMessages.suspended());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReactivated(TenantReactivatedEvent event) {
        send(event.tenantId(), event.schemaName(), "reactivation", PlanWhatsAppMessages.reactivated());
    }

    private void send(UUID tenantId, String schemaName, String kind, String text) {
        try {
            Optional<String> phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(schemaName);
            if (phone.isEmpty()) {
                log.warn("Tenant {} WhatsApp skipped for tenant {}: no owner WhatsApp phone", kind, tenantId);
                return;
            }
            whatsAppPort.sendReport(phone.get(), text);
            log.info("Tenant {} WhatsApp sent for tenant {}", kind, tenantId);
        } catch (RuntimeException e) {
            log.warn("Tenant {} WhatsApp failed for tenant {}: {}: {}",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
