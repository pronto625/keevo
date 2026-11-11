package com.keevo.messaging.whatsapp.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Story 19.9c — makes template variable values acceptable to Meta: a body parameter cannot hold a line break, a tab
 * or more than four consecutive spaces, cannot be empty, and the rendered body cannot exceed {@link #MAX_BODY}
 * characters. Whitespace runs become one space, or {@value #LINE_SEPARATOR} when they hold a line break.
 *
 * <p>Only a « detail » template (one variable carrying the message text) is shortened: its value is cut and ends with
 * {@code …}. A template with structured variables (names, reference, amount, link…) is never cut — a cut link or
 * reference would be wrong — so an oversized one gives {@link Optional#empty()} and the caller sends the free text.
 * O(total length). Lengths are UTF-16 units (≥ Meta's character count: safe).
 */
public final class TemplateParameters {

    /** Meta's limit of a template body once its variables are replaced. */
    public static final int MAX_BODY = 1024;
    static final String LINE_SEPARATOR = " · ";
    static final String ELLIPSIS = "…";
    static final String EMPTY = "-";

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private TemplateParameters() {
    }

    /** One value on one line, whitespace collapsed (single linear pass), never blank. */
    public static String flatten(String value) {
        if (value == null) return EMPTY;
        String collapsed = WHITESPACE.matcher(value.strip()).replaceAll(r -> {
            String run = r.group();
            return run.indexOf('\n') >= 0 || run.indexOf('\r') >= 0 ? LINE_SEPARATOR : " ";
        }).strip();
        return collapsed.isEmpty() ? EMPTY : collapsed;
    }

    /**
     * The values of {@code template}'s variables, flattened and fitted to {@link #MAX_BODY}; empty when a structured
     * template would only fit by cutting one of its values.
     */
    public static Optional<List<String>> fit(PlatformTemplate template, List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        int total = template.fixedLength();
        for (String v : values) {
            String flat = flatten(v);
            out.add(flat);
            total += flat.length();
        }
        if (total <= MAX_BODY) return Optional.of(out);
        if (out.size() != 1) return Optional.empty();
        int keep = MAX_BODY - template.fixedLength() - ELLIPSIS.length();
        if (keep < 1) return Optional.empty();
        String value = out.get(0);
        if (Character.isHighSurrogate(value.charAt(keep - 1))) keep--; // never split an emoji
        return Optional.of(List.of(value.substring(0, keep).stripTrailing() + ELLIPSIS));
    }
}
