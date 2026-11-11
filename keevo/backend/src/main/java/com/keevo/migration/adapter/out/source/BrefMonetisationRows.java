package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.migration.domain.model.BrefMonetisation.AccountCredit;
import com.keevo.migration.domain.model.BrefMonetisation.AppleTransaction;
import com.keevo.migration.domain.model.BrefMonetisation.PlanPaymentRequest;
import com.keevo.migration.domain.model.BrefMonetisation.RevenueCatEvent;
import com.keevo.migration.domain.model.BrefMonetisation.SmsRecharge;
import com.keevo.migration.domain.model.BrefMonetisation.Subscription;
import com.keevo.migration.domain.model.BrefMonetisation.UsageCounter;
import com.keevo.migration.domain.service.BrefMappings;

import static com.keevo.migration.adapter.out.source.BrefRows.instant;
import static com.keevo.migration.adapter.out.source.BrefRows.instantOrNull;
import static com.keevo.migration.adapter.out.source.BrefRows.text;
import static com.keevo.migration.adapter.out.source.BrefRows.uuid;

/**
 * Story 24.5 — the mapping of the monetisation rows (JSON with the Bref property names: the export's objects and the
 * database's {@code to_jsonb(row)}), shared by both section sources so they cannot drift. Amounts are integers (FCFA,
 * SMS units); an absent amount reads as {@code 0}.
 */
final class BrefMonetisationRows {

    private BrefMonetisationRows() {
    }

    static Subscription subscription(JsonNode n) {
        return new Subscription(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "plan"), text(n, "status"),
                text(n, "billingCycle"), instantOrNull(n, "startedAt"), instantOrNull(n, "endsAt"),
                amount(n, "amountPaid"), amount(n, "creditUsed"), text(n, "paymentMethod"),
                text(n, "appleOriginalTransactionId"), instantOrNull(n, "gracePeriodEndsAt"), instant(n, "createdAt"),
                instantOrNull(n, "updatedAt"));
    }

    static PlanPaymentRequest paymentRequest(JsonNode n) {
        return new PlanPaymentRequest(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "targetPlan"),
                text(n, "targetBillingCycle"), amount(n, "amount"), amount(n, "creditApplied"),
                amount(n, "amountDue"), text(n, "currency"), text(n, "status"), text(n, "paymentMethod"),
                instantOrNull(n, "startsAt"), instantOrNull(n, "endsAt"), instant(n, "createdAt"),
                instant(n, "updatedAt"), text(n, "paymentProvider"), text(n, "providerTransactionRef"),
                text(n, "providerStatus"));
    }

    static AppleTransaction appleTransaction(JsonNode n) {
        JsonNode reason = n.get("revocationReason");
        Integer revocationReason = reason == null || reason.isNull() ? null : BrefRows.integer(n, "revocationReason");
        return new AppleTransaction(uuid(n, "id"), text(n, "ownerId"), text(n, "originalTransactionId"),
                text(n, "transactionId"), text(n, "productId"), text(n, "bundleId"), text(n, "environment"),
                text(n, "status"), instant(n, "purchaseDate"), instantOrNull(n, "expiresDate"), revocationReason,
                instantOrNull(n, "revocationDate"), text(n, "subscriptionId"), instantOrNull(n, "createdAt"),
                instantOrNull(n, "updatedAt"));
    }

    static RevenueCatEvent revenueCatEvent(JsonNode n) {
        String id = text(n, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id missing");
        }
        return new RevenueCatEvent(id, text(n, "type"), text(n, "subjectKey"), amount(n, "eventTimestampMs"),
                instantOrNull(n, "processedAt"), instantOrNull(n, "createdAt"));
    }

    static AccountCredit accountCredit(JsonNode n) {
        return new AccountCredit(uuid(n, "id"), uuidOrNull(n, "ownerId"), amount(n, "amount"),
                instantOrNull(n, "updatedAt"));
    }

    static SmsRecharge smsRecharge(JsonNode n) {
        return new SmsRecharge(uuid(n, "id"), uuidOrNull(n, "userId"), amount(n, "smsQuantity"),
                amount(n, "amountDue"), text(n, "currency"), text(n, "status"), text(n, "paymentMethod"),
                instantOrNull(n, "completedAt"), instantOrNull(n, "createdAt"), instantOrNull(n, "updatedAt"));
    }

    static UsageCounter usageCounter(JsonNode n) {
        return new UsageCounter(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "metric"), text(n, "periodMonth"),
                amount(n, "used"), instantOrNull(n, "updatedAt"));
    }

    /** An integer amount from a JSON number or string; absent → {@code 0}. */
    static long amount(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.longValue();
        }
        if (v.isTextual()) {
            return Long.parseLong(v.asText().strip()); // NumberFormatException is an IllegalArgumentException
        }
        throw new IllegalArgumentException(field + " is not an integer");
    }

    private static java.util.UUID uuidOrNull(JsonNode n, String field) {
        return BrefMappings.uuidOrNull(text(n, field));
    }
}
