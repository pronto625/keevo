package com.keevo.messaging.channel.application;

import com.keevo.messaging.channel.domain.model.Recipients;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.EmailProvider;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Story 19.9 — {@link EmailSender} as a chain of responsibility over the providers, in the configured order
 * ({@code keevo.email.providers}); same rules as {@link FailoverSmsSender}. The sender is the configured address
 * ({@code keevo.email.from}) with the message's display name, or the configured one ({@code keevo.email.from-name}).
 * Logs mask the address ({@code j***@domain}) and never carry the subject, body or a key.
 */
public class FailoverEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(FailoverEmailSender.class);

    private final List<EmailProvider> providers;
    private final String fromAddress;
    private final String defaultFromName;

    public FailoverEmailSender(List<EmailProvider> providers, String fromAddress, String defaultFromName) {
        this.providers = List.copyOf(providers);
        this.fromAddress = fromAddress == null ? "" : fromAddress.strip();
        this.defaultFromName = defaultFromName;
        if (this.providers.isEmpty()) {
            log.info("[Email] channel disabled — no provider configured (keevo.email.providers)");
        } else {
            this.providers.forEach(p -> log.info("[Email] provider {}: {}", p.name(),
                    p.isConfigured() ? "configured" : "MISSING keys"));
        }
    }

    @Override
    public boolean isEnabled() {
        return !providers.isEmpty();
    }

    @Override
    public DeliveryResult send(EmailMessage message) {
        if (providers.isEmpty() || message == null) {
            return DeliveryResult.notDelivered();
        }
        if (!Recipients.isEmailAddress(message.to())) {
            log.warn("[Email] invalid address {} — not sent", Recipients.maskEmail(message.to()));
            return DeliveryResult.notDelivered();
        }
        EmailMessage normalized = new EmailMessage(message.to().strip(), message.subject(), message.text(),
                message.html(), message.fromName(), message.attachments());
        String sender = sender(message.fromName());
        for (EmailProvider provider : providers) {
            if (!provider.isConfigured()) {
                log.warn("[Email] {} skipped — keys not configured", provider.name());
                continue;
            }
            SendOutcome outcome;
            try {
                outcome = provider.send(normalized, sender);
            } catch (RuntimeException e) {
                log.warn("[Email] {} failed ({})", provider.name(), e.getClass().getSimpleName());
                continue;
            }
            if (outcome == SendOutcome.DELIVERED) {
                log.info("[Email] sent to {} via {}", Recipients.maskEmail(normalized.to()), provider.name());
                return DeliveryResult.deliveredBy(provider.name());
            }
            if (outcome == SendOutcome.UNKNOWN) {
                // the provider may have accepted it: no failover (no double send), reported uncertain (Story 20.8)
                log.warn("[Email] {} outcome unknown for {} — not retried elsewhere", provider.name(), Recipients.maskEmail(normalized.to()));
                return DeliveryResult.uncertain(provider.name());
            }
        }
        log.warn("[Email] not delivered to {} — every provider failed", Recipients.maskEmail(normalized.to()));
        return DeliveryResult.notDelivered();
    }

    /** {@code address} or {@code "Name" <address>} (RFC 5322; inner quotes replaced as Bref does, backslashes stripped). */
    String sender(String fromName) {
        String name = fromName != null && !fromName.isBlank() ? fromName : defaultFromName;
        if (name == null || name.isBlank()) {
            return fromAddress;
        }
        String safe = name.strip().replace("\\", "").replace('"', '\'').replaceAll("[\\r\\n<>]", " ");
        return '"' + safe + '"' + " <" + fromAddress + '>'; // RFC 5322 display name (not SQL)
    }
}
