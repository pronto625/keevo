package com.keevo.admin.subscription.application.service;

import com.keevo.shared.event.DeliveryFailedException;
import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.WhatsAppSender;
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
@DisplayName("TenantStatusWhatsAppNotificationListener (Story 9-2)")
class TenantStatusWhatsAppNotificationListenerTest {

    private static final String SCHEMA = "kv_abc123";
    private static final String PHONE = "+237600000001";
    private static final UUID TENANT = UUID.randomUUID();

    @Mock WhatsAppSender whatsApp;
    @Mock OwnerRecipientResolver resolver;
    @InjectMocks TenantStatusWhatsAppNotificationListener listener;

    @Test
    @DisplayName("suspension → « Votre compte Keevo est suspendu. Contactez le support Keevo. »")
    void suspended() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onSuspended(new TenantSuspendedEvent(UUID.randomUUID(), TENANT, SCHEMA, "fraude", Instant.now()));

        verify(whatsApp).sendReport(PHONE, "Votre compte Keevo est suspendu. Contactez le support Keevo.");
    }

    @Test
    @DisplayName("reactivation → « Votre compte Keevo est réactivé. »")
    void reactivated() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));

        listener.onReactivated(new TenantReactivatedEvent(UUID.randomUUID(), TENANT, SCHEMA, Instant.now()));

        verify(whatsApp).sendReport(PHONE, "Votre compte Keevo est réactivé.");
    }

    @Test
    @DisplayName("no owner phone → nothing sent")
    void noPhone() {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.empty());

        listener.onReactivated(new TenantReactivatedEvent(UUID.randomUUID(), TENANT, SCHEMA, Instant.now()));

        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("12.4 R2: WhatsApp down → DeliveryFailedException (retried); resolver failure → logged, skipped")
    void failures() throws Exception {
        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenReturn(Optional.of(PHONE));
        doThrow(new RuntimeException("down")).when(whatsApp).sendReport(anyString(), anyString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onSuspended(new TenantSuspendedEvent(
                UUID.randomUUID(), TENANT, SCHEMA, "fraude", Instant.now())))
                .isInstanceOf(DeliveryFailedException.class).hasRootCauseMessage("down");
        org.assertj.core.api.Assertions.assertThat(TenantStatusWhatsAppNotificationListener.class
                .getMethod("onSuspended", TenantSuspendedEvent.class)
                .isAnnotationPresent(com.keevo.shared.event.DeliverOnce.class)).isTrue();
        org.assertj.core.api.Assertions.assertThat(TenantStatusWhatsAppNotificationListener.class
                .getMethod("onReactivated", TenantReactivatedEvent.class)
                .isAnnotationPresent(com.keevo.shared.event.DeliverOnce.class)).isTrue();

        when(resolver.resolveOwnerWhatsAppPhone(SCHEMA)).thenThrow(new RuntimeException("db"));
        assertThatCode(() -> listener.onReactivated(new TenantReactivatedEvent(UUID.randomUUID(), TENANT, SCHEMA,
                Instant.now()))).doesNotThrowAnyException();
    }
}
