package com.keevo.whatsappcloud.template.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 21.5 — the variables of a template body (port of Bref {@code whatsapp-template-text.ts}): numbered
 * ({@code {{1}}}, {@code {{2}}}…) in number order, or named ({@code {{name}}}) in order of appearance; and the body with
 * its values, as the contact reads it (stored in the conversation).
 */
public final class TemplateText {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private TemplateText() {
    }

    /** The distinct variable keys, in sending order. */
    public static List<String> variables(String body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(body == null ? "" : body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        List<String> list = new ArrayList<>(keys);
        if (!list.isEmpty() && list.stream().allMatch(k -> k.matches("\\d+"))) {
            list.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        }
        return list;
    }

    /** The body with each variable replaced by its value (a missing value leaves the variable as is). */
    public static String render(String body, List<String> values) {
        List<String> keys = variables(body);
        Matcher m = VARIABLE.matcher(body == null ? "" : body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int index = keys.indexOf(m.group(1));
            String value = index >= 0 && index < values.size() && values.get(index) != null ? values.get(index)
                    : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
