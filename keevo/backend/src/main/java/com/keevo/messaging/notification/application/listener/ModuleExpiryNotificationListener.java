package com.keevo.messaging.notification.application.listener;

import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import com.keevo.shared.capability.notification.PlatformWhatsAppMessage;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.NewStatus;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Origin;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Story 16.1 (MOD-FR13) — tells the tenant owner, per run of the nightly licence job, which modules changed status
 * (after the tenant's commit, one grouped message per kind, French, display names only):
 * <ul>
 *   <li>(a) {@code ADMIN} rows that became {@code EXPIRED} — « Votre module X est arrivé à échéance : il reste
 *       consultable en lecture seule jusqu'au JJ/MM/AAAA. Contactez-nous pour le renouveler. » ({@code PLAN} rows are
 *       already covered by the plan expiry message);</li>
 *   <li>(b) every row that became {@code DISABLED} — « Les modules X, Y sont désactivés. Vos données sont conservées
 *       et réapparaîtront dès la réactivation. »</li>
 * </ul>
 * Channels: push to the owners ({@link PushNotifier#notifyOwners}) and WhatsApp to the primary owner
 * ({@link OwnerRecipientResolver}, as the plan messages). The transition itself is the de-duplication (a later run
 * finds nothing to change); {@link DeliverOnce}: a registry replay of the same event never sends twice. Best effort:
 * every failure is logged, never thrown — the rows are already committed.
 */
@Component
public class ModuleExpiryNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ModuleExpiryNotificationListener.class);

    static final String TYPE_EXPIRED = "MODULE_EXPIRED";
    static final String TYPE_DISABLED = "MODULE_DISABLED";
    static final String DEEP_LINK = "/settings/modules";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;
    private final OwnerRecipientResolver ownerRecipientResolver;

    public ModuleExpiryNotificationListener(PushNotifier pushNotifier, WhatsAppSender whatsAppSender,
                                            OwnerRecipientResolver ownerRecipientResolver) {
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
        this.ownerRecipientResolver = ownerRecipientResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @DeliverOnce
    public void onLicencesChanged(ModuleLicencesChangedEvent event) {
        try {
            long expired = event.transitions().stream()
                    .filter(t -> t.origin() == Origin.ADMIN && t.status() == NewStatus.EXPIRED).count();
            long disabled = event.transitions().stream().filter(t -> t.status() == NewStatus.DISABLED)
                    .map(Transition::displayName).distinct().count();
            expiredMessage(event.transitions()).ifPresent(text -> deliver(event.tenantId(), TYPE_EXPIRED,
                    expired > 1 ? "Modules arrivés à échéance" : "Module arrivé à échéance", text));
            disabledMessage(event.transitions()).ifPresent(text -> deliver(event.tenantId(), TYPE_DISABLED,
                    disabled > 1 ? "Modules désactivés" : "Module désactivé", text));
        } catch (RuntimeException e) {
            log.warn("Module licence notifications failed for tenant {} ({})", event.tenantId(),
                    e.getClass().getSimpleName());
        }
    }

    /** (a) — {@code ADMIN} rows turned {@code EXPIRED}, grouped by end of grace; empty when there are none. */
    static Optional<String> expiredMessage(List<Transition> transitions) {
        Map<LocalDate, List<String>> byDate = new TreeMap<>();
        List<String> withoutDate = new ArrayList<>();
        for (Transition t : transitions) {
            if (t.origin() != Origin.ADMIN || t.status() != NewStatus.EXPIRED) {
                continue;
            }
            if (t.graceUntil() == null) {
                withoutDate.add(t.displayName());
            } else {
                byDate.computeIfAbsent(date(t.graceUntil()), d -> new ArrayList<>()).add(t.displayName());
            }
        }
        List<String> sentences = new ArrayList<>();
        byDate.forEach((date, names) -> sentences.add(names.size() == 1
                ? "Votre module " + names.get(0) + " est arrivé à échéance : il reste consultable en lecture seule "
                        + "jusqu'au " + DATE.format(date) + "."
                : "Vos modules " + join(names) + " sont arrivés à échéance : ils restent consultables en lecture "
                        + "seule jusqu'au " + DATE.format(date) + "."));
        if (!withoutDate.isEmpty()) { // a row without grace never stays EXPIRED (disabled at once): defensive
            sentences.add(withoutDate.size() == 1
                    ? "Votre module " + withoutDate.get(0) + " est arrivé à échéance."
                    : "Vos modules " + join(withoutDate) + " sont arrivés à échéance.");
        }
        if (sentences.isEmpty()) {
            return Optional.empty();
        }
        boolean several = byDate.values().stream().mapToInt(List::size).sum() + withoutDate.size() > 1;
        return Optional.of(String.join("\n", sentences)
                + (several ? " Contactez-nous pour les renouveler." : " Contactez-nous pour le renouveler."));
    }

    /** (b) — every row turned {@code DISABLED}, one sentence; empty when there are none. */
    static Optional<String> disabledMessage(List<Transition> transitions) {
        List<String> names = transitions.stream().filter(t -> t.status() == NewStatus.DISABLED)
                .map(Transition::displayName).distinct().toList();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        String head = names.size() == 1 ? "Le module " + names.get(0) + " est désactivé."
                : "Les modules " + join(names) + " sont désactivés.";
        return Optional.of(head + " Vos données sont conservées et réapparaîtront dès la réactivation.");
    }

    private void deliver(String tenantSchema, String type, String title, String text) {
        try {
            pushNotifier.notifyOwners(tenantSchema, NotificationPayload.of(type, title, text, DEEP_LINK, Map.of()));
        } catch (RuntimeException e) {
            log.warn("Module licence push ({}) failed for tenant {} ({})", type, tenantSchema,
                    e.getClass().getSimpleName());
        }
        Optional<String> phone;
        try {
            phone = ownerRecipientResolver.resolveOwnerWhatsAppPhone(tenantSchema);
        } catch (RuntimeException e) {
            log.warn("Module licence WhatsApp ({}) skipped for tenant {}: owner lookup failed ({})", type,
                    tenantSchema, e.getClass().getSimpleName());
            return;
        }
        if (phone.isEmpty()) {
            log.warn("Module licence WhatsApp ({}) skipped for tenant {}: no owner WhatsApp phone", type,
                    tenantSchema);
            return;
        }
        try {
            whatsAppSender.sendNotification(phone.get(), PlatformWhatsAppMessage.detail(PlatformMessageKind.MODULE_LICENCE, text));
            log.info("Module licence WhatsApp ({}) sent for tenant {}", type, tenantSchema);
        } catch (RuntimeException e) {
            log.warn("Module licence WhatsApp ({}) failed for tenant {} ({})", type, tenantSchema,
                    e.getClass().getSimpleName());
        }
    }

    private static LocalDate date(Instant instant) {
        return instant.atZone(KeevoZones.WAT).toLocalDate();
    }

    /** « A, B, C » (display names, catalogue order). */
    private static String join(List<String> names) {
        return String.join(", ", names);
    }
}
