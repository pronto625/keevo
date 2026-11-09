package com.keevo.payments.checkout.application.listener;

import com.keevo.payments.checkout.domain.event.PaymentNoticeEvent;
import com.keevo.payments.checkout.domain.event.PlanPaymentConfirmedEvent;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.event.DeliverOnce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.keevo.shared.domain.time.KeevoZones.WAT;

/**
 * Story 21.1 — tells the owners, after the confirmation commit, that the payment was received: push + inbox
 * ({@link PushNotifier#notifyOwners}), type {@value #TYPE}, « Paiement reçu » / « Votre plan Pro est actif jusqu'au
 * 28/02/2027. », deep link {@value #DEEP_LINK}. The WhatsApp message goes through the plan activation event
 * (subscription). {@link DeliverOnce}: a replay never sends twice. Best effort: a failure is logged, never thrown.
 */
@Component
public class PaymentPushListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentPushListener.class);

    static final String TYPE = "PLAN_PAYMENT_CONFIRMED";
    static final String TITLE = "Paiement reçu";
    static final String DEEP_LINK = "/plan";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(WAT);

    private final PushNotifier pushNotifier;

    public PaymentPushListener(PushNotifier pushNotifier) {
        this.pushNotifier = pushNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onConfirmed(PlanPaymentConfirmedEvent event) {
        try {
            pushNotifier.notifyOwners(event.schemaName(), NotificationPayload.of(TYPE, TITLE, body(event),
                    DEEP_LINK, Map.of()));
        } catch (RuntimeException e) {
            log.warn("Payment confirmation push failed for tenant {} ({})", event.schemaName(),
                    e.getClass().getSimpleName());
        }
    }

    /** Story 21.3 — a pack credited, a late payment added to the credit: same channel, same guarantees. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onNotice(PaymentNoticeEvent event) {
        try {
            pushNotifier.notifyOwners(event.schemaName(), NotificationPayload.of(event.type(), event.title(),
                    event.body(), DEEP_LINK, Map.of()));
        } catch (RuntimeException e) {
            log.warn("Payment notice push failed for tenant {} ({})", event.schemaName(),
                    e.getClass().getSimpleName());
        }
    }

    static String body(PlanPaymentConfirmedEvent event) {
        return "Votre plan " + event.planLabel() + " est actif jusqu'au " + FR_DATE.format(event.expiresAt()) + ".";
    }
}
