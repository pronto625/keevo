package com.keevo.shared.domain;

/**
 * Story 20.4 — French display of an XAF amount in francs: digits grouped by three with a narrow no-break space
 * (U+202F), e.g. « 100 000 » (never split across lines). Shared by the invoicing messages and the audit details.
 */
public final class XafAmounts {

    /** Thousands separator: narrow no-break space (U+202F). */
    public static final char SEPARATOR = ' ';

    private XafAmounts() {
    }

    /** « 1 234 567 » (U+202F between groups), a minus sign for a negative amount. */
    public static String group(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder(amount < 0 ? "-" : "");
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                out.append(SEPARATOR);
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
