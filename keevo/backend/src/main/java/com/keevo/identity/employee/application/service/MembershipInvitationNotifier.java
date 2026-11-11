package com.keevo.identity.employee.application.service;

import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import com.keevo.shared.capability.notification.PlatformWhatsAppMessage;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Story 19.6b (replaces the 19.6 « member added » notifier) — the two messages of an invitation:
 * <ul>
 *   <li>to the invited person: one platform message, WhatsApp when the account has a phone and WhatsApp is configured,
 *       else (or on a WhatsApp failure) email when it has a verified email; never SMS (cost). The person has no access
 *       to the inviting company yet, so there is no inbox row;</li>
 *   <li>to the inviter, once the person accepted: one inbox notification in the company (type {@value #ACCEPTED_TYPE}).
 *       A refusal notifies nobody.</li>
 * </ul>
 * Fire-and-forget after commit (a rolled-back invitation notifies nobody); the platform message (an HTTP call) runs on
 * {@code notificationExecutor}. Failures are logged without personal data and never reach the caller.
 */
@Component
public class MembershipInvitationNotifier {

    private static final Logger log = LoggerFactory.getLogger(MembershipInvitationNotifier.class);

    static final String ACCEPTED_TYPE = "MEMBERSHIP_INVITATION_ACCEPTED";

    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;
    private final EmailSender emailSender;
    private final Executor executor;

    public MembershipInvitationNotifier(PushNotifier pushNotifier, WhatsAppSender whatsAppSender,
                                        EmailSender emailSender,
                                        @Qualifier("notificationExecutor") Executor executor) {
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
        this.emailSender = emailSender;
        this.executor = executor;
    }

    /** Schedules the invitation message to the person after the current transaction commits. */
    public void invitationCreated(User account, String companyName, String role) {
        afterCommit(() -> {
            String text = invitationText(companyName, role);
            PlatformWhatsAppMessage whatsApp = PlatformWhatsAppMessage.of(PlatformMessageKind.MEMBERSHIP_INVITATION,
                    text, company(companyName), MembershipInvitation.roleLabel(role));
            String subject = "Invitation à rejoindre " + company(companyName);
            try {
                executor.execute(() -> sendPlatformMessage(account, subject, whatsApp));
            } catch (RuntimeException e) {
                log.warn("[invitation] message not scheduled: {}", e.getClass().getSimpleName());
            }
        });
    }

    /** Schedules the inviter's inbox notification in {@code tenantSchema} after the acceptance commits. */
    public void invitationAccepted(String tenantSchema, UUID inviterId, String memberName) {
        if (inviterId == null) {
            return; // no inviter recorded
        }
        afterCommit(() -> {
            String name = memberName == null || memberName.isBlank() ? "Un nouveau membre" : memberName;
            try {
                pushNotifier.notifyUsers(tenantSchema, List.of(inviterId), NotificationPayload.of(ACCEPTED_TYPE,
                        name + " a rejoint votre équipe", "Votre invitation a été acceptée."));
            } catch (RuntimeException e) {
                log.warn("[invitation] inbox notification failed for tenant {}: {}", tenantSchema,
                        e.getClass().getSimpleName());
            }
        });
    }

    static String invitationText(String companyName, String role) {
        return company(companyName) + " vous invite à rejoindre son équipe sur Bref Point en tant que "
                + MembershipInvitation.roleLabel(role) + ". Ouvrez l'application pour accepter ou refuser. L'invitation expire dans 7 jours. "
                + "Si vous ne connaissez pas cette entreprise, ignorez ce message.";
    }

    private static String company(String companyName) {
        return companyName == null || companyName.isBlank() ? "Une entreprise" : companyName.strip();
    }

    /** WhatsApp when the account has a phone and WhatsApp is configured; on failure or otherwise, verified email. */
    void sendPlatformMessage(User account, String subject, PlatformWhatsAppMessage message) {
        String text = message.text();
        if (account.hasPhoneNumber() && whatsAppSender.isConfigured()) {
            try {
                whatsAppSender.sendNotification(account.getPhoneNumber(), message);
                return;
            } catch (RuntimeException e) {
                log.warn("[invitation] WhatsApp failed: {} — trying email", e.getClass().getSimpleName());
            }
        }
        try {
            if (account.getEmail() != null && account.getEmailVerifiedAt() != null && emailSender.isEnabled()) {
                emailSender.send(EmailMessage.of(account.getEmail(), subject, text));
            }
        } catch (RuntimeException e) {
            log.warn("[invitation] email failed: {}", e.getClass().getSimpleName());
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
