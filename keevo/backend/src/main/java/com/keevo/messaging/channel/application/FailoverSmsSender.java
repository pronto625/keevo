package com.keevo.messaging.channel.application;

import com.keevo.messaging.channel.domain.model.Recipients;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Story 19.9 — {@link SmsSender} as a chain of responsibility over the providers, in the configured order
 * ({@code keevo.sms.providers}). A provider without keys is skipped, a failure (exception, non-2xx, rejection) passes
 * to the next one; the first acceptance wins. An unknown outcome (read timeout)
 * stops the chain: no second provider, not delivered. No retry inside one provider. An empty chain disables the channel.
 *
 * <p>Logs carry the provider name and the HTTP status only, the phone masked to its last 3 digits; never a key nor the
 * message text. Built by {@code ChannelPropertiesConfig}.
 */
public class FailoverSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(FailoverSmsSender.class);

    private final List<SmsProvider> providers;
    private final String defaultSenderId;

    public FailoverSmsSender(List<SmsProvider> providers, String defaultSenderId) {
        this.providers = List.copyOf(providers);
        this.defaultSenderId = Recipients.normalizeSenderId(defaultSenderId, null);
        if (this.providers.isEmpty()) {
            log.info("[SMS] channel disabled — no provider configured (keevo.sms.providers)");
        } else {
            this.providers.forEach(p -> log.info("[SMS] provider {}: {}", p.name(),
                    p.isConfigured() ? "configured" : "MISSING keys"));
        }
    }

    @Override
    public boolean isEnabled() {
        return !providers.isEmpty();
    }

    @Override
    public DeliveryResult send(SmsMessage message) {
        if (providers.isEmpty() || message == null) {
            return DeliveryResult.notDelivered();
        }
        Optional<String> phone = Recipients.normalizePhone(message.to());
        if (phone.isEmpty()) {
            log.warn("[SMS] invalid phone {} — not sent", Recipients.maskPhone(message.to()));
            return DeliveryResult.notDelivered();
        }
        String senderId = Recipients.normalizeSenderId(message.senderId(), defaultSenderId);
        for (SmsProvider provider : providers) {
            if (!provider.isConfigured()) {
                log.warn("[SMS] {} skipped — keys not configured", provider.name());
                continue;
            }
            SendOutcome outcome;
            try {
                outcome = provider.send(phone.get(), message.text(), senderId);
            } catch (RuntimeException e) {
                log.warn("[SMS] {} failed ({})", provider.name(), e.getClass().getSimpleName());
                continue;
            }
            if (outcome == SendOutcome.DELIVERED) {
                log.info("[SMS] sent to {} via {}", Recipients.maskPhone(phone.get()), provider.name());
                return DeliveryResult.deliveredBy(provider.name());
            }
            if (outcome == SendOutcome.UNKNOWN) {
                // the provider may have accepted it: no failover (no double send), reported not delivered
                log.warn("[SMS] {} outcome unknown for {} — not retried elsewhere", provider.name(), Recipients.maskPhone(phone.get()));
                return DeliveryResult.notDelivered();
            }
        }
        log.warn("[SMS] not delivered to {} — every provider failed", Recipients.maskPhone(phone.get()));
        return DeliveryResult.notDelivered();
    }
}
