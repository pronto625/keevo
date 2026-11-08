package com.keevo.invoicing.invoice.application.service.channel;

import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

/** Story 20.8 — a metered provider outcome turned into the channel's answer. */
final class InvoiceDeliveryOutcomes {

    private InvoiceDeliveryOutcomes() {
    }

    /**
     * Delivered → returns; uncertain (sent, unanswered) → {@code INVOICE_SEND_UNCERTAIN}; otherwise → {@code failure}
     * (certainly not delivered).
     */
    static void require(DeliveryResult result, ErrorCode failure) {
        if (result != null && result.delivered()) {
            return;
        }
        if (result != null && result.uncertain()) {
            throw new DomainException(ErrorCode.INVOICE_SEND_UNCERTAIN, "Provider outcome unknown");
        }
        throw new DomainException(failure, "No provider delivered the message");
    }
}
