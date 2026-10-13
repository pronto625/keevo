package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.port.out.OwnerRecipientResolver;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.subscription.plan.domain.model.event.PlanActivatedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanDowngradedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * PlanWhatsAppNotificationListener — tells the tenant owner on WhatsApp that its plan changed
 * (Story 9-2): Premium activation, manual downgrade to FREE, natural expiry.
 *
 * <p>Runs AFTER_COMMIT of the plan change (no message for a rolled-back change); the recipient
 * comes from {@link OwnerRecipientResolver} only (v1s-14-17). No owner phone → warn and skip.
 * Every runtime failure (lookup or send) is logged and swallowed: the plan change is already
 * committed and must never fail because WhatsApp is down. {@code fallbackExecution} keeps the
 * message when the event is published outside a transaction.
 */
@Component
public class PlanWhatsAppNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PlanWhatsAppNotificationListener.class);

    private final WhatsAppPort whatsAppPort;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public PlanWhatsAppNotificationListener(WhatsAppPort whatsAppPort,
                                            OwnerRecipientResolver ownerRecipientResolver) {
        this.whatsAppPort = whatsAppPort;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivated(PlanActivatedEvent event) {
        send(event.tenantId(), event.schemaName(), "activation",
                PlanWhatsAppMessages.activated(event.expiresAt()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDowngraded(PlanDowngradedEvent event) {
        send(event.tenantId(), event.schemaName(), "downgrade", PlanWhatsAppMessages.downgraded());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onExpired(PlanExpiredEvent event) {
        send(event.tenantId(), event.schemaName(), "expiry", PlanWhatsAppMessages.expired());
    }

    private void send(UUID tenantId, String schemaName, String kind, String text) {
        try {
            Optional<String> phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(schemaName);
            if (phone.isEmpty()) {
                log.warn("Plan {} WhatsApp skipped for tenant {}: no owner WhatsApp phone", kind, tenantId);
                return;
            }
            whatsAppPort.sendReport(phone.get(), text);
            log.info("Plan {} WhatsApp sent for tenant {}", kind, tenantId);
        } catch (RuntimeException e) {
            log.warn("Plan {} WhatsApp failed for tenant {}: {}: {}",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
