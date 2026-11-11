package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import com.keevo.shared.capability.notification.PlatformWhatsAppMessage;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 19.6b — invitation message to the person (WhatsApp, else verified email, never SMS, no inbox) and the
 * inviter's inbox notification on acceptance; failures never reach the caller.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("19.6b — invitation notifier")
class MembershipInvitationNotifierTest {

    @Mock PushNotifier pushNotifier;
    @Mock WhatsAppSender whatsAppSender;
    @Mock EmailSender emailSender;

    MembershipInvitationNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new MembershipInvitationNotifier(pushNotifier, whatsAppSender, emailSender, Runnable::run);
        when(whatsAppSender.isConfigured()).thenReturn(true);
        when(emailSender.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("account with a phone → one WhatsApp with the company and the role label, no inbox, no email")
    void invitation_whatsApp() {
        User account = User.newOwner("+237690000001", "h");
        notifier.invitationCreated(account, "Boutique A", "MANAGER");

        ArgumentCaptor<PlatformWhatsAppMessage> message = ArgumentCaptor.forClass(PlatformWhatsAppMessage.class);
        verify(whatsAppSender).sendNotification(eq("+237690000001"), message.capture());
        // 19.9c — Meta template keevo_invitation_entreprise: company, role label
        assertThat(message.getValue().kind()).isEqualTo(PlatformMessageKind.MEMBERSHIP_INVITATION);
        assertThat(message.getValue().parameters()).containsExactly("Boutique A", "Gérant");
        assertThat(message.getValue().text()).startsWith("Boutique A vous invite à rejoindre son équipe sur Bref Point en tant "
                + "que Gérant.").contains("expire dans 7 jours").contains("ignorez ce message");
        verifyNoInteractions(pushNotifier, emailSender);
    }

    @Test
    @DisplayName("WhatsApp failure → verified email; unverified email → nothing; never throws")
    void invitation_emailFallback() {
        doThrow(new RuntimeException("down")).when(whatsAppSender).sendNotification(anyString(), any(PlatformWhatsAppMessage.class));
        User verified = User.newOwner("+237690000002", "h").withEmail("awa@example.cm")
                .withEmailVerifiedAt(Instant.now());
        notifier.invitationCreated(verified, null, "EMPLOYEE");
        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(mail.capture());
        assertThat(mail.getValue().toString()).contains("Une entreprise");

        reset(emailSender);
        when(emailSender.isEnabled()).thenReturn(true);
        User unverified = User.newEmployee(null, "awa.k", "h").withEmail("awa2@example.cm");
        notifier.invitationCreated(unverified, "Boutique A", "EMPLOYEE");
        verify(emailSender, never()).send(any());
    }

    @Test
    @DisplayName("acceptance → one inbox notification to the inviter in the company; no inviter → nothing")
    void accepted_inbox() {
        UUID inviter = UUID.randomUUID();
        notifier.invitationAccepted("kv_a", inviter, "Awa Mbarga");

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier).notifyUsers(eq("kv_a"), eq(List.of(inviter)), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo(MembershipInvitationNotifier.ACCEPTED_TYPE);
        assertThat(payload.getValue().title()).isEqualTo("Awa Mbarga a rejoint votre équipe");

        reset(pushNotifier);
        notifier.invitationAccepted("kv_a", null, "Awa");
        verifyNoInteractions(pushNotifier);
    }

    @Test
    @DisplayName("inbox failure is swallowed")
    void accepted_failureSwallowed() {
        doThrow(new RuntimeException("db")).when(pushNotifier).notifyUsers(any(), any(), any());
        notifier.invitationAccepted("kv_a", UUID.randomUUID(), "Awa");
    }
}
