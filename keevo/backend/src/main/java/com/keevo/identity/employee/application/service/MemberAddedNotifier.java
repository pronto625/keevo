package com.keevo.identity.employee.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Story 19.6 — tells a person that an existing account of theirs was added to a company: one inbox notification in
 * that company ({@link PushNotifier#notifyUsers}, type {@value #TYPE}), then one platform message, WhatsApp when the
 * account has a phone and WhatsApp is configured, else email when it has a verified email. Never SMS (cost).
 *
 * <p>Fire-and-forget after commit (a rolled-back addition notifies nobody): the inbox row is written on the request
 * thread, the platform message (an HTTP call) runs on {@code notificationExecutor}. A WhatsApp failure falls back to the
 * verified email. Every failure is logged without personal data and never reaches the caller.
 */
@Component
public class MemberAddedNotifier {

    private static final Logger log = LoggerFactory.getLogger(MemberAddedNotifier.class);

    static final String TYPE = "MEMBERSHIP_ADDED";

    private static final Map<String, String> ROLE_LABELS = Map.of(
            "OWNER", "Propriétaire", "MANAGER", "Gérant", "EMPLOYEE", "Employé", "VIEWER", "Lecteur");

    private final PushNotifier pushNotifier;
    private final WhatsAppSender whatsAppSender;
    private final EmailSender emailSender;
    private final Executor executor;

    public MemberAddedNotifier(PushNotifier pushNotifier, WhatsAppSender whatsAppSender, EmailSender emailSender,
                               @Qualifier("notificationExecutor") Executor executor) {
        this.pushNotifier = pushNotifier;
        this.whatsAppSender = whatsAppSender;
        this.emailSender = emailSender;
        this.executor = executor;
    }

    /** Schedules the notifications after the current transaction commits (immediately without a transaction). */
    public void notifyAfterCommit(User account, String tenantSchema, String companyName, Role role) {
        Runnable send = () -> send(account, tenantSchema, companyName, role);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    void send(User account, String tenantSchema, String companyName, Role role) {
        String name = companyName == null || companyName.isBlank() ? "une nouvelle entreprise" : companyName;
        String roleLabel = ROLE_LABELS.getOrDefault(role.name(), "Membre");
        try {
            pushNotifier.notifyUsers(tenantSchema, List.of(account.getId()), NotificationPayload.of(
                    TYPE, "Vous avez rejoint " + name, "Votre rôle : " + roleLabel + "."));
        } catch (RuntimeException e) {
            log.warn("[member-added] inbox notification failed for tenant {}: {}", tenantSchema,
                    e.getClass().getSimpleName());
        }
        String text = "Vous avez rejoint " + name + " en tant que " + roleLabel + ". Connectez-vous avec votre compte "
                + "habituel, puis choisissez cette entreprise.";
        String subject = "Vous avez rejoint " + name;
        try {
            executor.execute(() -> sendPlatformMessage(account, tenantSchema, subject, text));
        } catch (RuntimeException e) {
            log.warn("[member-added] platform message not scheduled for tenant {}: {}", tenantSchema,
                    e.getClass().getSimpleName());
        }
    }

    /** WhatsApp when the account has a phone and WhatsApp is configured; on failure or otherwise, verified email. */
    void sendPlatformMessage(User account, String tenantSchema, String subject, String text) {
        if (account.hasPhoneNumber() && whatsAppSender.isConfigured()) {
            try {
                whatsAppSender.sendReport(account.getPhoneNumber(), text);
                return;
            } catch (RuntimeException e) {
                log.warn("[member-added] WhatsApp failed for tenant {}: {} — trying email", tenantSchema,
                        e.getClass().getSimpleName());
            }
        }
        try {
            if (account.getEmail() != null && account.getEmailVerifiedAt() != null && emailSender.isEnabled()) {
                emailSender.send(EmailMessage.of(account.getEmail(), subject, text));
            }
        } catch (RuntimeException e) {
            log.warn("[member-added] email failed for tenant {}: {}", tenantSchema, e.getClass().getSimpleName());
        }
    }
}
