package com.keevo.messaging.channel.application;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Story 19.9 — {@link TenantMessaging}: probe the quota, send with no transaction, count on success.
 *
 * <p>Probe-then-count rather than reserve-then-refund: the probe reuses the guarded upsert of 19.8 (pack fallback
 * included) in a {@code REQUIRES_NEW} transaction forced to roll back, so an exhausted quota refuses before any HTTP
 * call. The count is a second {@code REQUIRES_NEW} transaction after delivery. Concurrent sends racing the last unit
 * can overrun the quota by at most their number: the post-send refusal is logged and absorbed (the SMS is gone).
 * Cost: 2 short transactions (one guarded upsert per quota period each) + 1..N provider calls.
 */
@Service
public class MeteredTenantMessaging implements TenantMessaging {

    private static final Logger log = LoggerFactory.getLogger(MeteredTenantMessaging.class);

    private final SmsSender smsSender;
    private final EmailSender emailSender;
    private final UsageQuotaPort quotas;
    private final TransactionTemplate requiresNew;

    public MeteredTenantMessaging(SmsSender smsSender, EmailSender emailSender, UsageQuotaPort quotas,
                                  PlatformTransactionManager transactionManager) {
        this.smsSender = smsSender;
        this.emailSender = emailSender;
        this.quotas = quotas;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public DeliveryResult sendSms(String tenantSchema, SmsMessage message) {
        return metered(tenantSchema, UsageMetric.SMS, () -> smsSender.send(message));
    }

    @Override
    public DeliveryResult sendEmail(String tenantSchema, EmailMessage message) {
        return metered(tenantSchema, UsageMetric.EMAILS, () -> emailSender.send(message));
    }

    /** Story 20.8 — the message is built after the probe (step 1), still before the send. */
    @Override
    public DeliveryResult sendEmail(String tenantSchema, Supplier<EmailMessage> message) {
        return metered(tenantSchema, UsageMetric.EMAILS, () -> emailSender.send(message.get()));
    }

    private DeliveryResult metered(String tenantSchema, UsageMetric metric, Supplier<DeliveryResult> send) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantMessaging must be called outside a transaction: the provider "
                    + "call would hold the usage counter lock");
        }
        // 1. probe: QUOTA_EXCEEDED propagates before anything is sent; the increment is always rolled back
        requiresNew.executeWithoutResult(status -> {
            status.setRollbackOnly();
            quotas.assertAndIncrementForSchema(tenantSchema, metric, 1);
        });
        // 2. send, no transaction open
        DeliveryResult result = send.get();
        if (result == null || !result.delivered()) {
            return result == null ? DeliveryResult.notDelivered() : result; // uncertain kept (Story 20.8), not counted
        }
        // 3. count the delivered unit
        try {
            requiresNew.executeWithoutResult(status -> quotas.assertAndIncrementForSchema(tenantSchema, metric, 1));
        } catch (RuntimeException e) {
            if (e instanceof DomainException d && d.getErrorCode() == ErrorCode.QUOTA_EXCEEDED) {
                log.warn("[Quota] {} sent past quota by a concurrent send — delivered, not counted", metric);
            } else {
                // the message is delivered: the caller must not see a failure (nor resend it)
                log.error("[Quota] {} delivered but not counted ({})", metric, e.getClass().getSimpleName());
            }
        }
        return result;
    }
}
