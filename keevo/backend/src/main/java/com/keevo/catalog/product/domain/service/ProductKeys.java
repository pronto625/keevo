package com.keevo.catalog.product.domain.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ProductKeys — Story 20.1: the deduplication key of a product, ported byte for byte from Bref
 * ({@code api-bref/src/product/product-key.ts}, same examples as its spec in {@code ProductKeysTest}).
 *
 * <p>Pure functions (Domain Service, no state, no framework): the key is {@code bc:<barcode>} when the reference looks
 * like a barcode (8–14 digits), else {@code nm:<normalized name>}; {@code null} for a blank name without barcode.
 * Free or random references are not reliable enough to deduplicate, so they never make the key.
 */
public final class ProductKeys {

    /** Maximum length of a barcode / free reference (column {@code products.barcode}). */
    public static final int BARCODE_MAX_LENGTH = 100;

    static final String BARCODE_TOO_LONG = "La référence ou le code-barres ne doit pas dépasser 100 caractères";

    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]");
    /** JavaScript's {@code \s} (Unicode white space + line terminators + BOM), not Java's ASCII-only {@code \s}. */
    private static final Pattern JS_WHITESPACE = Pattern.compile(
            "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");
    private static final Pattern BARCODE = Pattern.compile("^[0-9]{8,14}$");

    private ProductKeys() {
    }

    /** NFD, combining marks U+0300–U+036F removed, lower case (Locale.ROOT), white space collapsed, trimmed. */
    public static String normalizeName(String name) {
        if (name == null) return "";
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String collapsed = JS_WHITESPACE.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ");
        int start = 0;
        int end = collapsed.length();
        while (start < end && collapsed.charAt(start) == ' ') start++;
        while (end > start && collapsed.charAt(end - 1) == ' ') end--;
        return collapsed.substring(start, end);
    }

    /** True for 8 to 14 ASCII digits once trimmed. */
    public static boolean isBarcodeLike(String reference) {
        return reference != null && BARCODE.matcher(reference.trim()).matches();
    }

    /** Maximum length of a key (column {@code products.product_key}). */
    public static final int KEY_MAX_LENGTH = 250;

    /**
     * {@code bc:<trimmed barcode>} for a barcode-like reference, else {@code nm:<normalized name>}, else null; capped at
     * {@value #KEY_MAX_LENGTH} characters (NFD can lengthen a name).
     */
    public static String key(String name, String reference) {
        if (isBarcodeLike(reference)) return "bc:" + reference.trim();
        String nameKey = normalizeName(name);
        if (nameKey.isEmpty()) return null;
        String key = "nm:" + nameKey;
        return key.length() > KEY_MAX_LENGTH ? key.substring(0, KEY_MAX_LENGTH) : key;
    }

    /**
     * The stored form of a barcode / reference typed by a person: trimmed, blank → {@code null} (cleared).
     *
     * @throws DomainException {@code VALIDATION_ERROR} (422) over {@value #BARCODE_MAX_LENGTH} characters
     */
    public static String cleanBarcode(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > BARCODE_MAX_LENGTH) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, BARCODE_TOO_LONG);
        }
        return trimmed;
    }
}
