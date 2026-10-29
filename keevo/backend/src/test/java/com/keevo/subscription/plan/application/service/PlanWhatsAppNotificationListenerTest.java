package com.keevo.subscription.plan.application.service;

import com.keevo.shared.event.DeliveryFailedException;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.plan.PlanDowngradedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanWhatsAppNotificationListener (Story 9-2)")
class PlanWhatsAppNotificationListenerTest {

    private static final String SCHEMA = "kv_abc123";
    private static final String PHONE = "+237600000001";
    private static final UUID TENANT = UUID.randomUUID();

    @Mock WhatsAppSender whatsApp;
    @Mock OwnerRecipientResolver resolver;
    @InjectMocks PlanWhatsAppNotificationListener listener;

    @Test
    @DisplayName("activation → « 🎉 … activé jusqu'au dd/MM/yyyy … » (WAT date)")
    void activated() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onActivated(new PlanActivatedEvent(UUID.randomUUID(), TENANT, SCHEMA, PlanType.FREE,
                Instant.parse("2026-11-12T22:59:59Z"), 10_000, Instant.now()));

        verify(whatsApp).sendReport(PHONE,
                "🎉 Votre compte Keevo Premium est activé jusqu'au 12/11/2026. Profitez de toutes les fonctionnalités !");
    }

    @Test
    @DisplayName("downgrade → « … repassé au plan Gratuit. Vos données sont conservées. »")
    void downgraded() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onDowngraded(new PlanDowngradedEvent(UUID.randomUUID(), TENANT, SCHEMA, PlanType.PREMIUM, null, Instant.now()));

        verify(whatsApp).sendReport(PHONE,
                "Votre abonnement Keevo est repassé au plan Gratuit. Vos données sont conservées.");
    }

    @Test
    @DisplayName("expiry → « … Premium a expiré : … plan Gratuit. Vos données sont conservées. »")
    void expired() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onExpired(new PlanExpiredEvent(TENANT, SCHEMA, PlanType.PREMIUM, Instant.now()));

        verify(whatsApp).sendReport(PHONE,
                "Votre abonnement Keevo Premium a expiré : votre compte est passé au plan Gratuit. Vos données sont conservées.");
    }

    @Test
    @DisplayName("trial expiry → « Votre essai Keevo Premium est terminé … » (not the paid-subscription text)")
    void trialExpired() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onExpired(new PlanExpiredEvent(TENANT, SCHEMA, PlanType.PREMIUM_TRIAL, Instant.now()));

        verify(whatsApp).sendReport(PHONE,
                "Votre essai Keevo Premium est terminé : votre compte est passé au plan Gratuit. Vos données sont conservées.");
    }

    @Test
    @DisplayName("no owner phone → nothing sent")
    void noPhone() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.empty());

        listener.onExpired(new PlanExpiredEvent(TENANT, SCHEMA, PlanType.PREMIUM, Instant.now()));

        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("12.4 R2: WhatsApp down → DeliveryFailedException (publication stays incomplete, retried)")
    void sendFailure_propagatesForRetry() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));
        doThrow(new RuntimeException("down")).when(whatsApp).sendReport(anyString(), anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onDowngraded(new PlanDowngradedEvent(
                UUID.randomUUID(), TENANT, SCHEMA, PlanType.PREMIUM, null, Instant.now())))
                .isInstanceOf(DeliveryFailedException.class).hasRootCauseMessage("down");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onExpired(new PlanExpiredEvent(
                TENANT, SCHEMA, PlanType.PREMIUM, Instant.now())))
                .isInstanceOf(DeliveryFailedException.class);
    }

    @Test
    @DisplayName("12.4: the three senders are @DeliverOnce")
    void sendersAreDeliverOnce() throws Exception {
        for (var m : java.util.List.of(
                PlanWhatsAppNotificationListener.class.getMethod("onActivated", com.keevo.shared.event.catalog.plan.PlanActivatedEvent.class),
                PlanWhatsAppNotificationListener.class.getMethod("onDowngraded", PlanDowngradedEvent.class),
                PlanWhatsAppNotificationListener.class.getMethod("onExpired", PlanExpiredEvent.class))) {
            org.assertj.core.api.Assertions.assertThat(m.isAnnotationPresent(com.keevo.shared.event.DeliverOnce.class))
                    .as(m.getName()).isTrue();
        }
    }

    @Test
    @DisplayName("resolver failure → swallowed too")
    void resolverFailure_swallowed() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenThrow(new RuntimeException("db"));

        assertThatCode(() -> listener.onExpired(new PlanExpiredEvent(TENANT, SCHEMA, PlanType.PREMIUM, Instant.now())))
                .doesNotThrowAnyException();
        verifyNoInteractions(whatsApp);
    }
}
