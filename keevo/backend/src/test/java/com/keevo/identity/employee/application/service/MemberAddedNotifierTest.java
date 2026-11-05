package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Story 19.6 — notification of a person added to a company with an existing account. */
@ExtendWith(MockitoExtension.class)
@DisplayName("19.6 — MemberAddedNotifier")
class MemberAddedNotifierTest {

    @Mock PushNotifier pushNotifier;
    @Mock WhatsAppSender whatsAppSender;
    @Mock EmailSender emailSender;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Platform sends run on notificationExecutor; tests use a direct executor. */
    private MemberAddedNotifier notifier() {
        return new MemberAddedNotifier(pushNotifier, whatsAppSender, emailSender, Runnable::run);
    }

    private static User withPhone() {
        return User.newOwner("+237690000001", "h");
    }

    private static User emailOnly(Instant verifiedAt) {
        return new User(UUID.randomUUID(), null, null, Role.OWNER, true, Instant.now(), 0, null,
                "awa@example.cm", null, null, null, verifiedAt, null);
    }

    @Test
    @DisplayName("inbox notification MEMBERSHIP_ADDED in the new company + WhatsApp text when the account has a phone")
    void inboxAndWhatsApp() {
        User account = withPhone();
        when(whatsAppSender.isConfigured()).thenReturn(true);

        notifier().notifyAfterCommit(account, "kv_a", "Boutique Awa", Role.EMPLOYEE);

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier).notifyUsers(eq("kv_a"), eq(List.of(account.getId())), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("MEMBERSHIP_ADDED");
        assertThat(payload.getValue().title()).isEqualTo("Vous avez rejoint Boutique Awa");
        assertThat(payload.getValue().body()).contains("Employé");
        verify(whatsAppSender).sendReport(eq("+237690000001"), contains("Boutique Awa"));
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("no phone, verified email → email; unverified email → no platform message")
    void emailFallback() {
        when(emailSender.isEnabled()).thenReturn(true);

        notifier().notifyAfterCommit(emailOnly(Instant.now()), "kv_a", "Boutique Awa", Role.MANAGER);
        ArgumentCaptor<EmailMessage> mail = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("awa@example.cm");
        assertThat(mail.getValue().text()).contains("Gérant");

        clearInvocations(emailSender);
        notifier().notifyAfterCommit(emailOnly(null), "kv_a", "Boutique Awa", Role.MANAGER);
        verify(emailSender, never()).send(any());
        verify(whatsAppSender, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("WhatsApp failure → falls back to the verified email")
    void whatsAppFailure_fallsBackToEmail() {
        User account = new User(UUID.randomUUID(), "+237690000001", "h", Role.OWNER, true, Instant.now(), 0, null,
                "awa@example.cm", null, null, null, Instant.now(), null);
        when(whatsAppSender.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("provider down")).when(whatsAppSender).sendReport(anyString(), anyString());
        when(emailSender.isEnabled()).thenReturn(true);

        notifier().notifyAfterCommit(account, "kv_a", "Boutique Awa", Role.EMPLOYEE);

        verify(emailSender).send(argThat(m -> "awa@example.cm".equals(m.to())));
    }

    @Test
    @DisplayName("the platform message is handed to the executor (not sent on the request thread); the inbox is not")
    void platformMessageOnExecutor() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        MemberAddedNotifier notifier = new MemberAddedNotifier(pushNotifier, whatsAppSender, emailSender, queued::add);

        notifier.notifyAfterCommit(withPhone(), "kv_a", "B", Role.EMPLOYEE);

        verify(pushNotifier).notifyUsers(eq("kv_a"), any(), any());
        verifyNoInteractions(whatsAppSender);
        assertThat(queued).hasSize(1);
        when(whatsAppSender.isConfigured()).thenReturn(true);
        queued.get(0).run();
        verify(whatsAppSender).sendReport(eq("+237690000001"), anyString());
    }

    @Test
    @DisplayName("provider failures are swallowed: the request never fails")
    void failuresSwallowed() {
        doThrow(new IllegalStateException("inbox down")).when(pushNotifier).notifyUsers(any(), any(), any());
        when(whatsAppSender.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("provider down")).when(whatsAppSender).sendReport(anyString(), anyString());

        assertThatCode(() -> notifier().notifyAfterCommit(withPhone(), "kv_a", "B", Role.EMPLOYEE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inside a transaction → nothing sent before commit, sent on afterCommit")
    void sentAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        when(whatsAppSender.isConfigured()).thenReturn(true);

        notifier().notifyAfterCommit(withPhone(), "kv_a", "B", Role.EMPLOYEE);
        verifyNoInteractions(pushNotifier, whatsAppSender);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(pushNotifier).notifyUsers(eq("kv_a"), any(), any());
        verify(whatsAppSender).sendReport(anyString(), anyString());
    }
}
