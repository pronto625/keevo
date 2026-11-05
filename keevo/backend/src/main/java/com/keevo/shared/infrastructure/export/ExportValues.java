package com.keevo.shared.infrastructure.export;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed value converters for export dataset providers (Story v1s-18-2): turn raw native-query
 * values (JDBC / Hibernate types) into the types of {@code ExportColumnType}, and enum codes into
 * French labels.
 *
 * <p>Story v1s-16-20 — no technical data in a file: unknown codes are humanized
 * ({@link #humanize(String)}), never written raw; {@link #isTechnical(String)} is the rule the
 * export guard tests apply to every header and cell.
 */
public final class ExportValues {

    private ExportValues() {
    }

    /** Owner fallback name when the actor has no employee row (same rule as the audit log). */
    public static final String OWNER_LABEL = "Propriétaire";

    /** Readable text of a stock movement note written by an inventory validation. */
    public static final String INVENTORY_NOTE = "Ajustement d'inventaire";

    /** Prefix of the stock movement notes written by an inventory validation ({@code INVENTORY:<id>}). */
    public static final String INVENTORY_NOTE_PREFIX = "INVENTORY:";

    /** Time zone of every date shown to the user (West Africa Time). */
    public static final ZoneId WAT = ZoneId.of("Africa/Douala");

    /** A UUID anywhere in a text. */
    public static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** A raw SNAKE_CASE code ({@code PENDING_VALIDATION}). */
    public static final Pattern SNAKE_CODE = Pattern.compile("^[A-Z0-9]+(_[A-Z0-9]+)+$");

    /** A SNAKE_CASE code inside a longer text (« Statut : PENDING_VALIDATION »). */
    public static final Pattern EMBEDDED_SNAKE_CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\\b");

    private static final DateTimeFormatter SALE_REFERENCE = DateTimeFormatter.ofPattern(
            "'Vente du 'dd/MM/yyyy' à 'HH:mm:ss", Locale.FRENCH);

    public static String str(Object value) {
        return value != null ? value.toString() : null;
    }

    public static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof LocalDateTime l) return l.toInstant(ZoneOffset.UTC);
        if (value instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass());
    }

    public static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDateTime l) return l.toLocalDate();
        if (value instanceof String s) return LocalDate.parse(s);
        throw new IllegalArgumentException("Unsupported date value: " + value.getClass());
    }

    /** Integer XAF amounts and quantities: any number → {@code Long} (decimals rounded half-up). */
    public static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof BigDecimal b) return b.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        if (value instanceof Double || value instanceof Float) return Math.round(((Number) value).doubleValue());
        if (value instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Unsupported numeric value: " + value.getClass());
    }

    public static Boolean toBool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }

    /** French label of an enum code; unknown codes are humanized ({@code KIOSK} → « Kiosk »), null stays null. */
    public static String frLabel(Map<String, String> labels, Object code) {
        if (code == null) return null;
        String c = code.toString();
        String label = labels.get(c);
        return label != null ? label : humanize(c);
    }

    /**
     * Readable words of a code — same rule as the app ({@code AuditPresenter.humanize}):
     * {@code MOBILE_MONEY} → « Mobile money », {@code someKey} → « Some key ».
     */
    public static String humanize(String code) {
        if (code == null) return null;
        String spaced = code.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim()
                .toLowerCase(Locale.ROOT);
        if (spaced.isEmpty()) return code;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Whether a text shown to the user holds technical data: a UUID, a raw SNAKE_CASE code or JSON. */
    public static boolean isTechnical(String text) {
        if (text == null) return false;
        String t = text.trim();
        return UUID_PATTERN.matcher(t).find() || SNAKE_CODE.matcher(t).matches()
                || EMBEDDED_SNAKE_CODE.matcher(t).find() || t.contains("{\"") || t.contains("[\"")
                || t.startsWith("{") || t.startsWith("[");
    }

    /**
     * Readable reference of a sale: « Vente du 12/10/2026 à 14:32:05 — Boutique Centre » (WAT),
     * without the store part when it is unknown. Shared by {@code sales} and {@code sale_items} so
     * the two files can be matched.
     */
    public static String saleReference(Instant occurredAt, String store) {
        if (occurredAt == null) return null;
        String ref = SALE_REFERENCE.format(occurredAt.atZone(WAT));
        return store == null || store.isBlank() ? ref : ref + " — " + store.trim();
    }

    /** ASCII file-name part of a French label: « Journal d'audit » → {@code journal-d-audit}; null when empty. */
    public static String slug(String label) {
        if (label == null) return null;
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String s = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? null : s;
    }

    /**
     * Stock movement / transfer notes: an inventory validation note ({@code INVENTORY:<id>}) reads
     * « Ajustement d'inventaire »; any other UUID is removed (« Annulation vente <id> » →
     * « Annulation vente »). Null when nothing readable remains.
     */
    public static String notes(Object value) {
        if (value == null) return null;
        String s = value.toString();
        if (s.startsWith(INVENTORY_NOTE_PREFIX)) return INVENTORY_NOTE;
        String cleaned = UUID_PATTERN.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * SQL joins resolving the display name of a user id the way the audit adapter does: latest
     * {@code employees} row of that user (LATERAL, {@code LIMIT 1} — never duplicates a row), else
     * « Propriétaire » when the user's membership of the current company ({@link #CURRENT_TENANT_ID}) is OWNER — never
     * the global {@code users.role} (Story 19.6: a person may own another company), else null. Use with
     * {@link #actorNameSelect(String)} and the same alias. The {@code alias_m} join exposes that membership's role.
     *
     * @param userIdExpr SQL expression of the user id (e.g. {@code m.actor_id})
     * @param alias      unique alias prefix in the query
     */
    public static String actorJoins(String userIdExpr, String alias) {
        return " LEFT JOIN LATERAL (SELECT NULLIF(TRIM(CONCAT_WS(' ', emp.first_name, emp.last_name)), '') AS name"
                + " FROM employees emp WHERE emp.user_id = " + userIdExpr
                + " ORDER BY emp.updated_at DESC LIMIT 1) " + alias + "_e ON TRUE"
                + memberJoin(userIdExpr, alias);
    }

    /**
     * Story 19.6 — id of the company whose schema the query runs in (the tenant schema heads the search_path); an
     * uncorrelated scalar subquery on the {@code tenants.schema_name} unique index, evaluated once per query.
     */
    public static final String CURRENT_TENANT_ID =
            "(SELECT cur_t.id FROM public.tenants cur_t WHERE cur_t.schema_name = current_schema())";

    /**
     * Story 19.6 — {@code LEFT JOIN} of the membership of {@code userIdExpr} in the current company, aliased
     * {@code <alias>_m}: the role to read instead of the global {@code users.role}. At most one row (unique
     * {@code (user_id, tenant_id)}). Same construction as the other actor joins (suffixed alias).
     */
    public static String memberJoin(String userIdExpr, String alias) {
        return " LEFT JOIN public.user_tenant_memberships " + alias + "_m ON " + alias + "_m.user_id = " + userIdExpr
                + " AND " + alias + "_m.tenant_id = " + CURRENT_TENANT_ID;
    }

    /** Select expression of the actor name joined by {@link #actorJoins(String, String)}. */
    public static String actorNameSelect(String alias) {
        return "COALESCE(" + alias + "_e.name, CASE WHEN " + alias + "_m.role = 'OWNER' THEN '"
                + OWNER_LABEL + "' END)";
    }
}
