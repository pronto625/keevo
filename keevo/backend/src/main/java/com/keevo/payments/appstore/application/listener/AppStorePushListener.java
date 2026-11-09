package com.keevo.payments.appstore.application.listener;

import com.keevo.payments.appstore.domain.event.AppStoreSubscriptionNotice;
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
 * Story 21.2 — tells the owners, after the commit, what happened to the App Store subscription: push + inbox
 * ({@link PushNotifier#notifyOwners}), deep link {@value #DEEP_LINK}. {@link DeliverOnce}: a replayed publication never
 * sends twice (the notice carries the RevenueCat event id). Best effort: a failure is logged, never thrown.
 */
@Component
public class AppStorePushListener {

    private static final Logger log = LoggerFactory.getLogger(AppStorePushListener.class);
    static final String DEEP_LINK = "/plan";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(WAT);

    private final PushNotifier pushNotifier;

    public AppStorePushListener(PushNotifier pushNotifier) {
        this.pushNotifier = pushNotifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onNotice(AppStoreSubscriptionNotice notice) {
        try {
            pushNotifier.notifyOwners(notice.schemaName(), NotificationPayload.of(type(notice), title(notice),
                    body(notice), DEEP_LINK, Map.of()));
        } catch (RuntimeException e) {
            log.warn("App Store notice push failed for tenant {} ({})", notice.schemaName(),
                    e.getClass().getSimpleName());
        }
    }

    static String type(AppStoreSubscriptionNotice n) {
        return switch (n.kind()) {
            case ACTIVE, RENEWAL_RESUMED -> "APP_STORE_ACTIVE";
            case CANCELLED -> "APP_STORE_CANCELLED";
            case BILLING_ISSUE -> "APP_STORE_BILLING_ISSUE";
            case EXPIRED, REFUNDED, TRANSFERRED -> "APP_STORE_EXPIRED";
        };
    }

    static String title(AppStoreSubscriptionNotice n) {
        return switch (n.kind()) {
            case ACTIVE -> "Abonnement App Store actif";
            case RENEWAL_RESUMED -> "Renouvellement automatique réactivé";
            case CANCELLED -> "Renouvellement automatique désactivé";
            case BILLING_ISSUE -> "Problème de paiement App Store";
            case EXPIRED -> "Abonnement App Store terminé";
            case REFUNDED -> "Abonnement App Store remboursé";
            case TRANSFERRED -> "Abonnement App Store transféré";
        };
    }

    static String body(AppStoreSubscriptionNotice n) {
        String plan = n.planLabel() == null || n.planLabel().isBlank() ? "" : " " + n.planLabel();
        String until = n.until() == null ? null : FR_DATE.format(n.until());
        return switch (n.kind()) {
            case ACTIVE, RENEWAL_RESUMED -> until == null ? "Votre plan" + plan + " est actif."
                    : "Votre plan" + plan + " est actif jusqu'au " + until + ".";
            case CANCELLED -> until == null ? "Votre plan" + plan + " ne sera pas renouvelé."
                    : "Votre plan" + plan + " reste actif jusqu'au " + until + ", sans renouvellement.";
            case BILLING_ISSUE -> until == null
                    ? "Apple n'a pas pu renouveler votre abonnement. Mettez à jour votre moyen de paiement."
                    : "Apple n'a pas pu renouveler votre abonnement. Mettez à jour votre moyen de paiement avant le "
                    + until + ".";
            case EXPIRED -> "Votre abonnement App Store est terminé.";
            case REFUNDED -> "Votre abonnement App Store a été remboursé.";
            case TRANSFERRED -> "Votre abonnement App Store a été transféré vers un autre compte.";
        };
    }
}
