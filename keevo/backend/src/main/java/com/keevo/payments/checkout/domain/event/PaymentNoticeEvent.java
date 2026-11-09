package com.keevo.payments.checkout.domain.event;

import java.util.UUID;

/**
 * Story 21.3 — an owner notice about a payment other than a plan activation (published inside the confirmation
 * transaction, sent after commit as push + inbox): a pack credited, or a payment received late added to the account
 * credit.
 *
 * @param schemaName company schema ({@code kv_xxxxxx})
 * @param requestId  the payment request (de-duplication of the delivery receipt)
 * @param type       notification type ({@value #PACK_CREDITED} / {@value #PAYMENT_CREDITED})
 * @param title      French title
 * @param body       French text
 */
public record PaymentNoticeEvent(String schemaName, UUID requestId, String type, String title, String body) {

    public static final String PACK_CREDITED = "USAGE_PACK_CREDITED";
    public static final String PAYMENT_CREDITED = "PAYMENT_CREDITED";
}
