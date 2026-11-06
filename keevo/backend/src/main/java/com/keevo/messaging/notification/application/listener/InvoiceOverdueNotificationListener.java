package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.catalog.invoicing.InvoicesOverdueEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Story 20.4 — tells the tenant owners, once per run of the daily overdue job, which invoices became « En retard »
 * (after the tenant's commit): push + inbox ({@link PushNotifier#notifyOwners}), type {@value #TYPE}, title « Factures en
 * retard », body « 3 facture(s) en retard de paiement : FAC-2026-000012, … » (numbers only, never an id), deep link to
 * the overdue list. The job's transition is the de-duplication (a later run finds nothing new); {@link DeliverOnce}: a
 * registry replay of the same event never sends twice. Best effort: a failure is logged, never thrown.
 */
@Component
public class InvoiceOverdueNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceOverdueNotificationListener.class);

    static final String TYPE = "INVOICE_OVERDUE";
    static final String TITLE = "Factures en retard";
    static final String DEEP_LINK = "/invoices?paymentState=OVERDUE";
    /** Numbers listed in the body; beyond, « … ». */
    static final int MAX_NUMBERS = 5;

    private final PushNotifier pushNotifier;

    public InvoiceOverdueNotificationListener(PushNotifier pushNotifier) {
        this.pushNotifier = pushNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onOverdue(InvoicesOverdueEvent event) {
        if (event.numbers().isEmpty()) {
            return;
        }
        try {
            pushNotifier.notifyOwners(event.tenantId(), NotificationPayload.of(TYPE, TITLE, body(event.numbers()),
                    DEEP_LINK, Map.of("count", String.valueOf(event.numbers().size()))));
        } catch (RuntimeException e) {
            log.warn("Invoice overdue notification failed for tenant {} ({})", event.tenantId(),
                    e.getClass().getSimpleName());
        }
    }

    /** « 3 facture(s) en retard de paiement : FAC-2026-000012, FAC-2026-000015, FAC-2026-000020 ». */
    static String body(List<String> numbers) {
        List<String> shown = numbers.size() > MAX_NUMBERS ? numbers.subList(0, MAX_NUMBERS) : numbers;
        return numbers.size() + " facture(s) en retard de paiement : " + String.join(", ", shown)
                + (numbers.size() > MAX_NUMBERS ? ", …" : "");
    }
}
