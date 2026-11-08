package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.invoicing.invoice.application.service.InvoiceRecipients;
import com.keevo.invoicing.invoice.application.service.InvoiceSendMessages;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.capability.notification.TenantMessaging;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Story 20.8 — SMS sending (Bref's text + the public link, GSM-7, at most 2 segments) through
 * {@link TenantMessaging}: quota probed before, counted ({@code SMS}) only after a provider accepted it. The provider
 * outcome is a value ({@link DeliveryResult}), never an exception: an exception here is a programming error and
 * propagates.
 */
@Component
public class SmsInvoiceChannel implements InvoiceSendChannel {

    private final SmsSender smsSender;
    private final TenantMessaging messaging;

    public SmsInvoiceChannel(SmsSender smsSender, TenantMessaging messaging) {
        this.smsSender = smsSender;
        this.messaging = messaging;
    }

    @Override
    public SendChannel channel() {
        return SendChannel.SMS;
    }

    @Override
    public String recipient(Invoice invoice, String override) {
        return InvoiceRecipients.smsPhone(InvoiceRecipients.pick(override, invoice.client().phone()));
    }

    @Override
    public void assertAvailable() {
        if (!smsSender.isEnabled()) {
            throw new DomainException(ErrorCode.SMS_UNAVAILABLE, "No SMS provider configured");
        }
    }

    @Override
    public void deliver(Message m) {
        String text = InvoiceSendMessages.sms(m.invoice().isQuote(), m.reference(), m.sellerName(), m.link());
        // QUOTA_EXCEEDED (DomainException) propagates from the probe: nothing sent
        DeliveryResult result = messaging.sendSms(m.tenantSchema(), SmsMessage.of(m.to(), text));
        InvoiceDeliveryOutcomes.require(result, ErrorCode.SMS_DELIVERY_FAILED);
    }
}
