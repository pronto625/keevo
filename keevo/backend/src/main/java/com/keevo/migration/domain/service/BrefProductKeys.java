package com.keevo.migration.domain.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Story 24.4 — Bref's product identity key of a sold line, ported from {@code api-bref/src/product/product-key.ts}
 * ({@code origin/develop} 6faa8b4): the barcode when the reference is one (8–14 digits), else the normalized name
 * ({@code NFD}, combining marks U+0300–U+036F removed, lower-cased, runs of JavaScript white space collapsed to one
 * space, trimmed). A line's key finds the product Bref recorded for it in the same shop.
 */
public final class BrefProductKeys {

    /** JavaScript's {@code \s} (and {@code String.prototype.trim}) set — Java's {@code \s} is ASCII only. */
    private static final String JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029"
            + "\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern SPACES = Pattern.compile("[" + JS_SPACE + "]+");
    private static final Pattern EDGES = Pattern.compile("^[" + JS_SPACE + "]+|[" + JS_SPACE + "]+$");
    private static final Pattern MARKS = Pattern.compile("[\\u0300-\\u036F]");
    private static final Pattern BARCODE = Pattern.compile("^[0-9]{8,14}$");

    private BrefProductKeys() {
    }

    /** {@code normalizeProductName}. */
    public static String normalizeName(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFD);
        n = MARKS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
        n = SPACES.matcher(n).replaceAll(" ");
        return EDGES.matcher(n).replaceAll("");
    }

    /** {@code isBarcodeLike}: an 8–14 digit reference once trimmed. */
    public static boolean barcodeLike(String reference) {
        return reference != null && BARCODE.matcher(trim(reference)).matches();
    }

    /** {@code buildProductKey(name, reference)}: {@code bc:<code>}, {@code nm:<name>}, or {@code null}. */
    public static String key(String name, String reference) {
        if (barcodeLike(reference)) {
            return "bc:" + trim(reference);
        }
        String nameKey = name == null ? "" : normalizeName(trim(name));
        return nameKey.isEmpty() ? null : "nm:" + nameKey;
    }

    private static String trim(String s) {
        return EDGES.matcher(s).replaceAll("");
    }
}
