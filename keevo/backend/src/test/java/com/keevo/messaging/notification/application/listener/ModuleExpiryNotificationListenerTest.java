package com.keevo.messaging.notification.application.listener;

import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.NewStatus;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Origin;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Transition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 16.1 — grouping, French wording, channels and failure handling of the module licence notifications. */
class ModuleExpiryNotificationListenerTest {

    private static final String TENANT = "kv_ntf001";
    private static final String PHONE = "+237690000000";
    /** 2026-11-08 23:30 UTC = 2026-11-09 00:30 in Douala: the date shown is the Douala one. */
    private static final Instant GRACE = Instant.parse("2026-11-08T23:30:00Z");

    private final PushNotifier push = mock(PushNotifier.class);
    private final WhatsAppSender whatsApp = com.keevo.shared.capability.notification.WhatsAppSenderMocks.forwardNotifications(mock(WhatsAppSender.class));
    private final OwnerRecipientResolver owners = mock(OwnerRecipientResolver.class);
    private final ModuleExpiryNotificationListener listener =
            new ModuleExpiryNotificationListener(push, whatsApp, owners);

    private static Transition t(String id, String name, Origin origin, NewStatus status, Instant grace) {
        return new Transition(id, name, origin, status, grace);
    }

    private static ModuleLicencesChangedEvent event(Transition... transitions) {
        return new ModuleLicencesChangedEvent(TENANT, List.of(transitions), null, Instant.now());
    }

    @Test
    @DisplayName("(a) ADMIN row expired → push + WhatsApp, French text with the Douala grace date, no id")
    void adminExpiry() {
        when(owners.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of(PHONE));

        listener.onLicencesChanged(event(t("invoicing", "Facturation", Origin.ADMIN, NewStatus.EXPIRED, GRACE)));

        String expected = "Votre module Facturation est arrivé à échéance : il reste consultable en lecture seule "
                + "jusqu'au 09/11/2026. Contactez-nous pour le renouveler.";
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().body()).isEqualTo(expected);
        assertThat(payload.getValue().type()).isEqualTo("MODULE_EXPIRED");
        assertThat(payload.getValue().title()).isEqualTo("Module arrivé à échéance");
        assertThat(payload.getValue().deepLink()).isEqualTo("/settings/modules");
        verify(whatsApp).sendReport(PHONE, expected);
    }

    @Test
    @DisplayName("PLAN rows expired → no module message (the plan expiry message covers them)")
    void planExpiryNotRepeated() {
        listener.onLicencesChanged(event(t("invoicing", "Facturation", Origin.PLAN, NewStatus.EXPIRED, GRACE),
                t("alerts", "Alertes", Origin.PLAN, NewStatus.EXPIRED, GRACE)));
        verify(push, never()).notifyOwners(anyString(), any());
        verify(whatsApp, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("(b) 8 PLAN rows disabled → one grouped message listing the 8 names")
    void disabledGrouped() {
        when(owners.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of(PHONE));
        List<String> names = List.of("Rapports avancés", "Alertes", "Inventaire tournant", "Exports",
                "Facturation", "Multi-boutiques", "Rentabilité", "Configuration des rapports");
        Transition[] transitions = names.stream()
                .map(n -> t(n.toLowerCase().replace(' ', '-'), n, Origin.PLAN, NewStatus.DISABLED, GRACE))
                .toArray(Transition[]::new);

        listener.onLicencesChanged(event(transitions));

        String expected = "Les modules " + String.join(", ", names) + " sont désactivés. Vos données sont "
                + "conservées et réapparaîtront dès la réactivation.";
        verify(whatsApp, times(1)).sendReport(PHONE, expected);
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push, times(1)).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("MODULE_DISABLED");
        assertThat(payload.getValue().title()).isEqualTo("Modules désactivés");
        assertThat(payload.getValue().body()).isEqualTo(expected);
    }

    @Test
    @DisplayName("wording: single disabled module, several ADMIN expiries on one date")
    void wording() {
        assertThat(ModuleExpiryNotificationListener.disabledMessage(List.of(
                t("invoicing", "Facturation", Origin.ADMIN, NewStatus.DISABLED, GRACE)))).contains(
                "Le module Facturation est désactivé. Vos données sont conservées et réapparaîtront dès la "
                        + "réactivation.");
        assertThat(ModuleExpiryNotificationListener.expiredMessage(List.of(
                t("invoicing", "Facturation", Origin.ADMIN, NewStatus.EXPIRED, GRACE),
                t("alerts", "Alertes", Origin.ADMIN, NewStatus.EXPIRED, GRACE)))).contains(
                "Vos modules Facturation, Alertes sont arrivés à échéance : ils restent consultables en lecture "
                        + "seule jusqu'au 09/11/2026. Contactez-nous pour les renouveler.");
        assertThat(ModuleExpiryNotificationListener.expiredMessage(List.of(
                t("invoicing", "Facturation", Origin.ADMIN, NewStatus.DISABLED, GRACE)))).isEmpty();
    }

    @Test
    @DisplayName("mixed run: (a) and (b) are sent separately; failures (push, lookup, WhatsApp) never propagate")
    void mixedAndFailures() {
        when(owners.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of(PHONE));
        doThrow(new IllegalStateException("fcm down")).when(push).notifyOwners(anyString(), any());
        doThrow(new IllegalStateException("provider down")).when(whatsApp).sendReport(anyString(), anyString());

        assertThatCode(() -> listener.onLicencesChanged(event(
                t("invoicing", "Facturation", Origin.ADMIN, NewStatus.EXPIRED, GRACE),
                t("alerts", "Alertes", Origin.PLAN, NewStatus.DISABLED, GRACE)))).doesNotThrowAnyException();
        verify(whatsApp, times(2)).sendReport(eq(PHONE), anyString());

        when(owners.resolveOwnerWhatsAppPhone(TENANT)).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> listener.onLicencesChanged(event(
                t("alerts", "Alertes", Origin.PLAN, NewStatus.DISABLED, GRACE)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("titles: singular for one module, plural for several ADMIN expiries")
    void titles() {
        listener.onLicencesChanged(event(t("invoicing", "Facturation", Origin.ADMIN, NewStatus.EXPIRED, GRACE),
                t("alerts", "Alertes", Origin.ADMIN, NewStatus.EXPIRED, GRACE)));
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().title()).isEqualTo("Modules arrivés à échéance");
    }

    @Test
    @DisplayName("the sender is @DeliverOnce: a registry replay never sends twice")
    void senderIsDeliverOnce() throws Exception {
        assertThat(ModuleExpiryNotificationListener.class
                .getMethod("onLicencesChanged", ModuleLicencesChangedEvent.class)
                .isAnnotationPresent(com.keevo.shared.event.DeliverOnce.class)).isTrue();
    }

    @Test
    @DisplayName("no owner phone → push only; one disabled module → singular title")
    void noPhone() {
        when(owners.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.empty());
        listener.onLicencesChanged(event(t("alerts", "Alertes", Origin.PLAN, NewStatus.DISABLED, GRACE)));
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(push).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().title()).isEqualTo("Module désactivé");
        verify(whatsApp, never()).sendReport(anyString(), anyString());
    }
}
