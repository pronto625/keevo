package com.keevo.payments.appstore.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Story 21.2 — a RevenueCat webhook event ({@code {"api_version", "event": {…}}}), read tolerantly: unknown fields are
 * ignored, a missing optional field is {@code null}, numbers may come as numbers or strings.
 *
 * @param tenantCode the {@code keevo_tenant} subscriber attribute (company code set by the app before purchase)
 */
public record AppStoreEvent(String id, String type, String appUserId, String originalTransactionId,
                            String transactionId, String productId, String store, String environment,
                            long eventTimestampMs, Instant purchasedAt, Instant expiresAt, Instant graceExpiresAt,
                            String cancelReason, String periodType, BigDecimal priceInPurchasedCurrency,
                            String currency, List<String> transferredFrom, List<String> transferredTo,
                            String tenantCode) {

    public static final String APP_STORE = "APP_STORE";
    /** Subscriber attribute holding the company code (set by the app with {@code Purchases.setAttributes}). */
    public static final String TENANT_ATTRIBUTE = "keevo_tenant";
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_ID = 128;
    private static final int MAX_REF = 100;

    /** The event of a webhook body, empty when there is no event or no usable id. */
    public static Optional<AppStoreEvent> parse(Map<String, Object> body) {
        if (body == null || !(body.get("event") instanceof Map<?, ?> raw)) {
            return Optional.empty();
        }
        String id = text(raw.get("id"), MAX_ID);
        String type = text(raw.get("type"), 64);
        if (id == null || type == null) {
            return Optional.empty();
        }
        return Optional.of(new AppStoreEvent(id, type.toUpperCase(Locale.ROOT), text(raw.get("app_user_id"), 255),
                text(raw.get("original_transaction_id"), MAX_REF), text(raw.get("transaction_id"), MAX_REF),
                text(raw.get("product_id"), MAX_REF), upper(text(raw.get("store"), 30)),
                upper(text(raw.get("environment"), 20)), number(raw.get("event_timestamp_ms")),
                instant(raw.get("purchased_at_ms")), instant(raw.get("expiration_at_ms")),
                instant(raw.get("grace_period_expiration_at_ms")), upper(text(raw.get("cancel_reason"), 40)),
                upper(text(raw.get("period_type"), 20)), decimal(raw.get("price_in_purchased_currency")),
                upper(text(raw.get("currency"), 3)), list(raw.get("transferred_from")),
                list(raw.get("transferred_to")), attribute(raw.get("subscriber_attributes"))));
    }

    /** The Keevo user of the RevenueCat customer, empty for an anonymous or foreign id. */
    public Optional<UUID> appUser() {
        return uuid(appUserId);
    }

    public boolean sandbox() {
        return "SANDBOX".equals(environment);
    }

    /** A refund through Apple support (RevenueCat CANCELLATION reason). */
    public boolean refund() {
        return "CUSTOMER_SUPPORT".equals(cancelReason);
    }

    public static Optional<UUID> uuid(String raw) {
        if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(raw));
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant instant(Object value) {
        long ms = number(value);
        return ms > 0 ? Instant.ofEpochMilli(ms) : null;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> list(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                String s = text(item, 255);
                if (s != null && out.size() < 10) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String attribute(Object attributes) {
        if (attributes instanceof Map<?, ?> map && map.get(TENANT_ATTRIBUTE) instanceof Map<?, ?> entry) {
            return upper(text(entry.get("value"), 20));
        }
        return null;
    }
}
