package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.DeliveryFailedException;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.plan.PlanDowngradedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
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
 * The plan change is already committed and never fails because WhatsApp is down.
 *
 * <p>Story 12.4: activation and downgrade come from the admin module (cross-module, published inside
 * {@code AdminPlanService}'s transactions) → durable module listeners (async, publication registry,
 * tenant bound from {@code schemaName}). The expiry event is published and consumed inside subscription
 * (inside {@code SubscriptionExpiryService}'s per-tenant transaction): synchronous after commit, still
 * recorded by the registry. All three are {@link DeliverOnce} (a replay never sends twice); a provider
 * failure on the send is rethrown as {@link DeliveryFailedException} (ruling R2) so the publication stays
 * incomplete and is retried — after commit, it never reaches the publisher. No owner phone / failed
 * owner lookup → warn and skip (not retried).
 */
@Component
public class PlanWhatsAppNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PlanWhatsAppNotificationListener.class);

    private final WhatsAppSender whatsAppSender;
    private final OwnerRecipientResolver ownerRecipientResolver;
    private final PlanCatalog catalog;

    /** With the seeded plan catalogue (tests). */
    public PlanWhatsAppNotificationListener(WhatsAppSender whatsAppSender,
                                            OwnerRecipientResolver ownerRecipientResolver) {
        this(whatsAppSender, ownerRecipientResolver, PlanCatalog.SEED);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlanWhatsAppNotificationListener(WhatsAppSender whatsAppSender,
                                            OwnerRecipientResolver ownerRecipientResolver, PlanCatalog catalog) {
        this.whatsAppSender = whatsAppSender;
        this.ownerRecipientResolver = ownerRecipientResolver;
        this.catalog = catalog;
    }

    @ApplicationModuleListener
    @DeliverOnce
    public void onActivated(PlanActivatedEvent event) {
        send(event.tenantId(), event.schemaName(), "activation",
                PlanWhatsAppMessages.activated(label(event.planCode()), event.expiresAt()));
    }

    @ApplicationModuleListener
    @DeliverOnce
    public void onDowngraded(PlanDowngradedEvent event) {
        send(event.tenantId(), event.schemaName(), "downgrade", PlanWhatsAppMessages.downgraded());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onExpired(PlanExpiredEvent event) {
        Optional<PlanDefinition> previous = catalog.find(event.previousPlan());
        send(event.tenantId(), event.schemaName(), "expiry", PlanWhatsAppMessages.expired(
                previous.map(PlanDefinition::kind).orElse(PlanKind.PAID),
                previous.map(PlanDefinition::label).orElse(null)));
    }

    /** Story 19.7b — catalogue label of the plan, {@code null} for an absent or unknown code (neutral text). */
    private String label(PlanType plan) {
        return catalog.find(plan).map(PlanDefinition::label).orElse(null);
    }

    private void send(UUID tenantId, String schemaName, String kind, String text) {
        Optional<String> phone;
        try {
            phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(schemaName);
        } catch (RuntimeException e) {
            log.warn("Plan {} WhatsApp skipped for tenant {}: owner lookup failed: {}: {}",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (phone.isEmpty()) {
            log.warn("Plan {} WhatsApp skipped for tenant {}: no owner WhatsApp phone", kind, tenantId);
            return;
        }
        try {
            whatsAppSender.sendReport(phone.get(), text);
        } catch (RuntimeException e) {
            // Story 12.4 (R2): rethrown — the publication stays incomplete and is retried
            log.warn("Plan {} WhatsApp failed for tenant {}: {}: {} — will be retried",
                    kind, tenantId, e.getClass().getSimpleName(), e.getMessage());
            throw new DeliveryFailedException("Plan " + kind + " WhatsApp send failed", e);
        }
        log.info("Plan {} WhatsApp sent for tenant {}", kind, tenantId);
    }
}
