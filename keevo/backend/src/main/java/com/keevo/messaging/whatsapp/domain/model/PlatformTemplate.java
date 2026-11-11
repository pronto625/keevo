package com.keevo.messaging.whatsapp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 19.9c — one Meta WhatsApp message template of the Keevo platform number, as it must be created in Meta
 * Business Manager: name, category, language, body with {@code {{n}}} variables, optional footer and one example
 * value per variable (Meta requires them for the review).
 *
 * @param name     Meta template name ({@code [a-z0-9_]})
 * @param category Meta category
 * @param language Meta language code ({@code fr})
 * @param body     body text with {@code {{1}}}…{@code {{n}}}
 * @param footer   footer text, {@code null} when none
 * @param examples one example per variable, in order
 */
public record PlatformTemplate(String name, Category category, String language, String body, String footer,
                               List<String> examples) {

    /** Meta template categories (Keevo platform messages are UTILITY; codes are AUTHENTICATION). */
    public enum Category { UTILITY, AUTHENTICATION, MARKETING }

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\d+)}}");

    public PlatformTemplate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(body, "body");
        examples = List.copyOf(examples);
    }

    /** Highest variable index of the body ({@code {{n}}}); the catalogue test checks the numbering is 1..n. */
    public int variableCount() {
        Matcher m = VARIABLE.matcher(body);
        int max = 0;
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    /** Body length without its {@code {{n}}} placeholders: the fixed characters of a rendered message. */
    public int fixedLength() {
        return VARIABLE.matcher(body).replaceAll("").length();
    }

    /** The body with its variables replaced by {@code values} (in order). */
    public String render(List<String> values) {
        Matcher m = VARIABLE.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1)) - 1;
            String value = index >= 0 && index < values.size() ? values.get(index) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
