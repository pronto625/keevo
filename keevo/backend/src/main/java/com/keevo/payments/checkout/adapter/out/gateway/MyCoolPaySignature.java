package com.keevo.payments.checkout.adapter.out.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Story 21.1 — signature of a MyCoolPay callback, ported from Bref ({@code MyCoolPayAdapter.computeSignature}):
 * {@code md5(transaction_ref + transaction_type + transaction_amount + transaction_currency + transaction_operator +
 * privateKey)}, lower-case hex. Values are concatenated as JavaScript's {@code String(value)} would print them (numbers
 * without a trailing {@code .0}, absent key → {@code undefined}, JSON null → {@code null}) so a callback signed for
 * Bref verifies here. Comparison in constant time.
 */
public final class MyCoolPaySignature {

    private MyCoolPaySignature() {
    }

    public static String sign(Map<String, Object> payload, String privateKey) {
        String text = js(payload, "transaction_ref") + js(payload, "transaction_type")
                + js(payload, "transaction_amount") + js(payload, "transaction_currency")
                + js(payload, "transaction_operator") + privateKey;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** True when {@code payload.signature} is the signature of the payload with this key (constant time). */
    public static boolean verify(Map<String, Object> payload, String privateKey) {
        if (payload == null || privateKey == null || privateKey.isBlank()) {
            return false;
        }
        Object given = payload.get("signature");
        if (!(given instanceof String signature) || signature.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(sign(payload, privateKey).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String js(Map<String, Object> payload, String key) {
        if (!payload.containsKey(key)) {
            return "undefined";
        }
        Object v = payload.get(key);
        if (v == null) {
            return "null";
        }
        if (v instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        }
        return v.toString();
    }
}
