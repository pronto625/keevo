package com.keevo.shared.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AuditChangesFormatter — readable French text of an audit entry's before/after values and
 * context (Story v1s-16-20), written into the {@code audit_log} export instead of raw JSON.
 *
 * <p>Pure and total: malformed JSON gives an empty text, never an exception. Same rules as the app
 * {@code AuditPresenter.changes / context / formatValue} (labels from {@link AuditLabels}):
 * <ul>
 *   <li>hidden keys ({@link AuditLabels#HIDDEN_KEYS}, {@code id}, {@code *Id}, {@code *Ids}) and
 *       UUID values are dropped, nested objects are dropped;</li>
 *   <li>lists → « n élément(s) » (« n article(s) » for sale items), {@code fieldsChanged} → field
 *       labels joined;</li>
 *   <li>booleans Oui/Non, amounts « 1 500 XAF », quantities « 3 unité(s) », dates
 *       {@code dd/MM/yyyy HH:mm} (WAT), codes through {@link AuditLabels#VALUES} else humanized,
 *       inventory notes « Ajustement d'inventaire ».</li>
 * </ul>
 */
public final class AuditChangesFormatter {

    private AuditChangesFormatter() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SEPARATOR = " ; ";
    static final String NONE = "—";
    private static final String ITEMS_KEY = "itemsSnapshot";
    private static final String FIELDS_CHANGED_KEY = "fieldsChanged";
    private static final String AUTOMATIC_KEY = "isAutomatic";

    private static final Set<String> AMOUNT_KEYS = Set.of("totalAmount", "discountAmount", "appliedPrice",
            "cataloguePrice", "totalRevenue", "price", "sellingPrice", "purchasePrice", "costPrice", "unitPrice",
            "buyPrice", "transportCost");

    private static final Set<String> QUANTITY_KEYS = Set.of("quantity", "quantityBefore", "quantityChange",
            "quantityAfter", "currentQuantity", "threshold", "lowStockThreshold", "requestedQuantity",
            "availableQuantity", "theoretical", "physical", "ecart");

    private static final Set<String> SIGNED_KEYS = Set.of("quantityChange", "ecart");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // ── Changes ─────────────────────────────────────────────────────────────────

    /**
     * « Champ : avant → après » joined by « ; » (a creation, without a before snapshot, reads
     * « Champ : valeur »). Null when nothing readable remains.
     */
    public static String changes(String valueBefore, String valueAfter) {
        Object before = decode(valueBefore);
        Object after = decode(valueAfter);
        List<String> parts = new ArrayList<>();
        if ((before instanceof List || after instanceof List) && !(before instanceof Map) && !(after instanceof Map)) {
            // Whole snapshots that are lists (sale items).
            String b = before instanceof List<?> l ? items(ITEMS_KEY, l.size()) : NONE;
            String a = after instanceof List<?> l ? items(ITEMS_KEY, l.size()) : NONE;
            return line(fieldLabel(ITEMS_KEY), before == null ? null : b, a);
        }
        Map<String, Object> b = asMap(before);
        Map<String, Object> a = asMap(after);
        List<String> keys = new ArrayList<>(a.keySet());
        for (String k : b.keySet()) {
            if (!keys.contains(k)) keys.add(k);
        }
        for (String k : keys) {
            if (isHiddenKey(k) || AuditLabels.CONTEXT.containsKey(k)) continue;
            Object bv = b.get(k);
            Object av = a.get(k);
            if (bv == null && av == null) continue;
            if (bv instanceof Map || av instanceof Map) continue; // nested objects: no readable rendering
            String fb = formatValue(k, bv);
            String fa = formatValue(k, av);
            if (fb == null || fa == null) continue; // a UUID value: the row says nothing readable
            if (before != null && after != null && fb.equals(fa)) continue; // unchanged field of a snapshot
            parts.add(line(fieldLabel(k), before == null ? null : fb, fa));
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    private static String line(String label, String before, String after) {
        return before == null ? label + " : " + after : label + " : " + before + " → " + after;
    }

    // ── Details ─────────────────────────────────────────────────────────────────

    /**
     * Context of the entry (« Motif : … ; Déclenchement : Automatique ») from {@code metadata}, or
     * for legacy rows from the context keys still in {@code value_after}. Null when empty.
     */
    public static String details(String metadata, String valueAfter) {
        Map<String, Object> meta = asMap(decode(metadata));
        Map<String, Object> source;
        if (!meta.isEmpty()) {
            source = meta;
        } else {
            Map<String, Object> after = asMap(decode(valueAfter));
            source = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : after.entrySet()) {
                if (AuditLabels.CONTEXT.containsKey(e.getKey())) source.put(e.getKey(), e.getValue());
            }
        }
        List<String> parts = new ArrayList<>();
        source.forEach((k, v) -> {
            if (isHiddenKey(k) || v == null || v instanceof Map) return;
            String value;
            if (AUTOMATIC_KEY.equals(k)) {
                value = Boolean.TRUE.equals(v) || "true".equals(v) ? "Automatique" : "Manuel";
            } else {
                value = formatValue(k, v);
            }
            if (value == null || value.isBlank() || NONE.equals(value)) return;
            parts.add(contextLabel(k) + " : " + value);
        });
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    // ── Rules ───────────────────────────────────────────────────────────────────

    /** Technical keys never shown: the hidden table, {@code id}, {@code *Id}, {@code *Ids}. */
    public static boolean isHiddenKey(String key) {
        return AuditLabels.HIDDEN_KEYS.contains(key) || "id".equals(key) || key.endsWith("Id")
                || key.endsWith("Ids");
    }

    public static String fieldLabel(String key) {
        String label = AuditLabels.FIELDS.get(key);
        return label != null ? label : ExportValues.humanize(key);
    }

    static String contextLabel(String key) {
        String label = AuditLabels.CONTEXT.get(key);
        if (label != null) return label;
        return fieldLabel(key);
    }

    /** French label of a code value: the table, else humanized. */
    public static String valueLabel(String code) {
        String label = AuditLabels.VALUES.get(code);
        return label != null ? label : ExportValues.humanize(code);
    }

    /** Readable value of one field; null when the value must not be shown (UUID). */
    static String formatValue(String key, Object v) {
        if (v == null) return NONE;
        if (v instanceof Boolean b) return b ? "Oui" : "Non";
        if (v instanceof List<?> list) {
            if (FIELDS_CHANGED_KEY.equals(key) && list.stream().allMatch(x -> x instanceof String)) {
                return String.join(", ", list.stream().map(x -> fieldLabel((String) x)).toList());
            }
            return items(key, list.size());
        }
        if (v instanceof Map) return null;
        if (v instanceof Number n) {
            if (isAmountKey(key)) return xaf(n);
            if (QUANTITY_KEYS.contains(key)) {
                String sign = SIGNED_KEYS.contains(key) && n.doubleValue() > 0 ? "+" : "";
                return sign + number(n) + " unité(s)";
            }
            return number(n);
        }
        String s = v.toString().trim();
        if (s.isEmpty()) return NONE;
        if (s.startsWith(ExportValues.INVENTORY_NOTE_PREFIX)) return ExportValues.INVENTORY_NOTE;
        if (ExportValues.UUID_PATTERN.matcher(s).find()) {
            s = ExportValues.notes(s); // ids removed; nothing left → not shown
            if (s == null) return null;
        }
        if (isAmountKey(key)) {
            try {
                return xaf(new java.math.BigDecimal(s));
            } catch (NumberFormatException ignored) {
                // not a number: shown as text
            }
        }
        if (AuditLabels.CODE_KEYS.contains(key) && s.matches("[A-Z0-9_]+")) return valueLabel(s);
        if (ExportValues.SNAKE_CODE.matcher(s).matches()) return valueLabel(s);
        if (key.endsWith("At") || "from".equals(key) || "to".equals(key)) {
            Instant i = parseInstant(s);
            if (i != null) return DATE_TIME.format(i.atZone(ExportValues.WAT));
        }
        if (s.startsWith("{") || s.startsWith("[")) return null; // JSON text
        return s;
    }

    static boolean isAmountKey(String key) {
        return AMOUNT_KEYS.contains(key) || key.endsWith("Price") || key.endsWith("Amount");
    }

    private static String items(String key, int n) {
        return ITEMS_KEY.equals(key) ? n + " article(s)" : n + " élément(s)";
    }

    /** {@code 1 500 XAF} (integer XAF, space grouping). */
    static String xaf(Number amount) {
        return group(Math.round(amount.doubleValue())) + " XAF";
    }

    private static String number(Number v) {
        double d = v.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) return group(v.longValue());
        String plain = new java.math.BigDecimal(v.toString()).abs().toPlainString();
        int dot = plain.indexOf('.');
        String dec = dot >= 0 ? plain.substring(dot + 1) : "";
        return (d < 0 ? "-" : "") + group((long) Math.abs(d)) + (dec.isEmpty() ? "" : "," + dec);
    }

    private static String group(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return value < 0 ? "-" + out : out.toString();
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    // ── JSON ────────────────────────────────────────────────────────────────────

    private static Object decode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return JSON.readValue(raw, Object.class);
        } catch (Exception e) {
            return null; // malformed: nothing readable
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
