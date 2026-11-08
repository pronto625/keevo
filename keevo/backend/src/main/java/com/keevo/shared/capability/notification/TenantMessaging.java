package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — sends an SMS or email on behalf of a tenant and counts it on the tenant's quota
 * ({@link com.keevo.shared.capability.entitlement.UsageMetric#SMS} / {@code EMAILS}).
 *
 * <p>Probe, send, count:
 * <ol>
 *   <li>the quota is probed in a short transaction that is rolled back: an exhausted quota throws
 *       {@code QUOTA_EXCEEDED} (403) here, <b>before anything is sent</b>;</li>
 *   <li>the message is sent with no transaction open (no counter row lock is held during the HTTP call);</li>
 *   <li>only when delivered, the unit is counted in a new short transaction. If a concurrent send took the last unit
 *       meanwhile, the message is already delivered: the refusal is logged and absorbed (bounded overrun).</li>
 * </ol>
 * A failed send is never counted. Platform messages (OTP, welcome) never go through here: they use
 * {@link SmsSender} / {@link EmailSender} directly.
 *
 * <p>Must not be called inside an active transaction ({@link IllegalStateException}).
 */
public interface TenantMessaging {

    /**
     * @param tenantSchema tenant schema ({@code kv_xxxxxx})
     * @throws com.keevo.shared.domain.exception.DomainException {@code QUOTA_EXCEEDED} before sending
     * @throws IllegalStateException when called inside an active transaction
     */
    DeliveryResult sendSms(String tenantSchema, SmsMessage message);

    /** Same, for an email ({@code EMAILS} metric). */
    DeliveryResult sendEmail(String tenantSchema, EmailMessage message);

    /**
     * Story 20.8 — same, the email built only after the quota probe passed (e.g. a PDF attachment rendered only when
     * the email may leave). An exception of {@code message} propagates; nothing is sent nor counted.
     */
    default DeliveryResult sendEmail(String tenantSchema, java.util.function.Supplier<EmailMessage> message) {
        return sendEmail(tenantSchema, message.get());
    }
}
