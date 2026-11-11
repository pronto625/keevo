package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.BrefShop;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Story 24.2 — one mapping from a Bref row as JSON (property names of the Bref entities) to the snapshot model, shared
 * by both sources: the export's objects and the database's {@code to_jsonb(row)} — so the two adapters cannot drift.
 * A property the source does not have (an older Bref schema) reads as absent ({@code null} / its Bref default).
 * Timestamps: ISO-8601 with an offset (export), or without (Bref {@code timestamp} columns, UTC).
 */
final class BrefRows {

    private BrefRows() {
    }

    static BrefAccount account(JsonNode n) {
        return new BrefAccount(uuid(n, "id"), text(n, "role"), text(n, "email"), bool(n, "emailVerified", false),
                text(n, "phone"), bool(n, "isVerified", false), text(n, "username"), text(n, "password"),
                text(n, "firstName"), text(n, "lastName"), text(n, "shopId"), bool(n, "isActive", true),
                bool(n, "mustChangePassword", false), text(n, "provider"), text(n, "socialId"),
                instant(n, "createdAt"), instant(n, "updatedAt"), instantOrNull(n, "deletedAt"));
    }

    /** An embedded logo larger than this (base64 text) is refused before decoding (store logos are ≤ 200 KB). */
    static final int MAX_PHOTO_BASE64 = 2 * 1024 * 1024;

    static BrefShop shop(JsonNode n) {
        JsonNode data = n.get("photoData");
        byte[] photo = null;
        if (data != null && !data.isNull()) {
            String base64 = text(data, "base64");
            if (base64 == null) {
                throw new IllegalArgumentException("photoData.base64 missing");
            }
            if (base64.length() > MAX_PHOTO_BASE64) {
                throw new IllegalArgumentException("photoData too large");
            }
            photo = Base64.getDecoder().decode(base64);
        }
        return new BrefShop(uuid(n, "id"), uuid(n, "userId"), text(n, "name"), text(n, "address"),
                text(n, "category"), text(n, "description"), text(n, "phone"), text(n, "photoUrl"), text(n, "niu"),
                text(n, "rccm"), text(n, "country"), bool(n, "isActive", true), instant(n, "createdAt"),
                instant(n, "updatedAt"), instantOrNull(n, "deletedAt"), photo);
    }

    /** Story 24.3 — a Bref {@code product} row. Decimals: a JSON string (export) or number. */
    static BrefProduct product(JsonNode n) {
        return new BrefProduct(uuid(n, "id"), text(n, "shopId"), text(n, "name"), text(n, "nameKey"),
                text(n, "productKey"), text(n, "reference"), decimal(n, "unitPrice"), bool(n, "priceManual", false),
                integer(n, "timesSold"), decimal(n, "totalQuantity"), decimal(n, "totalRevenue"),
                instantOrNull(n, "lastSoldAt"), bool(n, "isArchived", false), text(n, "mergedIntoId"),
                instant(n, "createdAt"), instant(n, "updatedAt"));
    }

    /** Story 24.3 — a Bref {@code customer} row. */
    static BrefCustomer customer(JsonNode n) {
        String consentBy = text(n, "marketingConsentById");
        return new BrefCustomer(uuid(n, "id"), text(n, "shopId"), text(n, "name"), text(n, "phone"),
                text(n, "email"), text(n, "type"), text(n, "nui"), text(n, "rccm"), text(n, "address"),
                text(n, "contactName"), text(n, "website"), text(n, "birthDate"), text(n, "notes"),
                integer(n, "totalInvoices"), decimal(n, "totalSpent"), instantOrNull(n, "lastPurchaseAt"),
                instantOrNull(n, "marketingConsentAt"), com.keevo.migration.domain.service.BrefMappings.uuidOrNull(consentBy),
                instantOrNull(n, "marketingOptOutAt"), instant(n, "createdAt"), instant(n, "updatedAt"));
    }

    /**
     * Story 24.4 — a Bref {@code invoice} row with its lines and payments: the export nests them ({@code items},
     * {@code payments}); the database adapter reads them apart and passes them here ({@code null} → read the nested
     * arrays, absent → none).
     */
    static BrefInvoice invoice(JsonNode n, List<BrefInvoiceItem> items, List<BrefInvoicePayment> payments) {
        UUID id = uuid(n, "id");
        List<BrefInvoiceItem> lines = items;
        if (lines == null) {
            lines = new ArrayList<>();
            for (JsonNode item : array(n, "items")) {
                lines.add(invoiceItem(item, id));
            }
        }
        List<BrefInvoicePayment> rows = payments;
        if (rows == null) {
            rows = new ArrayList<>();
            for (JsonNode payment : array(n, "payments")) {
                rows.add(invoicePayment(payment, id));
            }
        }
        return new BrefInvoice(id, text(n, "userId"), text(n, "shopId"), text(n, "customerName"),
                text(n, "customerPhone"), text(n, "customerEmail"), text(n, "customerNui"), text(n, "customerRccm"),
                text(n, "customerAddress"), text(n, "customerContactName"), text(n, "customerWebsite"),
                decimal(n, "subtotal"), decimal(n, "tax"), decimal(n, "discount"), decimal(n, "deliveryFee"),
                decimal(n, "total"), text(n, "status"), text(n, "paymentMethod"), decimal(n, "initialPayment"),
                instantOrNull(n, "dueDate"), text(n, "notes"), text(n, "date"), text(n, "publicToken"),
                instantOrNull(n, "publicTokenExpiresAt"), instantOrNull(n, "cancelledAt"), text(n, "cancelledBy"),
                text(n, "cancellationReason"), text(n, "type"), instantOrNull(n, "validatedAt"),
                instant(n, "createdAt"), instant(n, "updatedAt"), instantOrNull(n, "deletedAt"), lines, rows);
    }

    /** Story 24.4 — a Bref {@code invoice_item} row; {@code invoiceId} absent (nested in the export) → the parent's. */
    static BrefInvoiceItem invoiceItem(JsonNode n, UUID parent) {
        return new BrefInvoiceItem(uuid(n, "id"), parentId(n, parent), text(n, "reference"), text(n, "name"),
                text(n, "description"), decimal(n, "quantity"), decimal(n, "unitPrice"), decimal(n, "total"));
    }

    /** Story 24.4 — a Bref {@code invoice_payment} row; {@code invoiceId} absent → the parent's. */
    static BrefInvoicePayment invoicePayment(JsonNode n, UUID parent) {
        return new BrefInvoicePayment(uuid(n, "id"), parentId(n, parent), decimal(n, "amount"), text(n, "dueDate"),
                bool(n, "isPaid", false), text(n, "paidDate"), text(n, "notes"), instant(n, "createdAt"),
                instant(n, "updatedAt"));
    }

    /** Story 24.4 — a Bref {@code expense} row. */
    static BrefExpense expense(JsonNode n) {
        return new BrefExpense(uuid(n, "id"), text(n, "ownerId"), text(n, "shopId"), text(n, "createdById"),
                decimal(n, "amount"), text(n, "category"), text(n, "description"), text(n, "expenseDate"),
                text(n, "paymentMethod"), instant(n, "createdAt"), instant(n, "updatedAt"),
                instantOrNull(n, "deletedAt"));
    }

    private static UUID parentId(JsonNode n, UUID parent) {
        String v = text(n, "invoiceId");
        if (v == null) {
            if (parent == null) {
                throw new IllegalArgumentException("invoiceId missing");
            }
            return parent;
        }
        return UUID.fromString(v);
    }

    private static Iterable<JsonNode> array(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new IllegalArgumentException(field + " is not an array");
        }
        return v;
    }

    /** A decimal from a JSON string ({@code "12500.00"}, the contract) or number; absent → {@code 0}. */
    static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return BigDecimal.ZERO;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(field + " is not a decimal");
        }
        return new BigDecimal(v.asText().strip()); // NumberFormatException is an IllegalArgumentException
    }

    static int integer(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (!v.canConvertToInt()) {
            throw new IllegalArgumentException(field + " is not an integer");
        }
        return v.intValue();
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static boolean bool(JsonNode n, String field, boolean fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (!v.isBoolean()) {
            throw new IllegalArgumentException(field + " is not a boolean");
        }
        return v.booleanValue();
    }

    static UUID uuid(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return UUID.fromString(v);
    }

    static Instant instant(JsonNode n, String field) {
        Instant v = instantOrNull(n, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return v;
    }

    static Instant instantOrNull(JsonNode n, String field) {
        String v = text(n, field);
        if (v == null) {
            return null;
        }
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            parsed = LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        }
        // the api-bref export goes through JavaScript dates (milliseconds): the database's microseconds are dropped
        // so both adapters give the same values and the same checksums
        return parsed.truncatedTo(ChronoUnit.MILLIS);
    }
}
