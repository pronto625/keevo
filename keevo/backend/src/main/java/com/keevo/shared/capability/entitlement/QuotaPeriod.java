package com.keevo.shared.capability.entitlement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Story 19.8 — the period kind of a quota ({@code public.plan_quotas.period}) and the key of its counter
 * ({@code public.usage_counters.period_key}): {@code 2026-11} for a month, {@code 2026-11-04} for a day,
 * {@code LIFETIME}. Days and months are those of the business zone (WAT); per-country zones are story 20.5.
 */
public enum QuotaPeriod {
    /** Calendar month; may be paid from a pack once exhausted. */
    MONTH("par mois"),
    /** Calendar day: an anti-abuse cap, never paid from a pack. */
    DAY("par jour"),
    /** The tenant's whole life (e.g. FREE's 5 SMS); may be paid from a pack once exhausted. */
    LIFETIME("à vie");

    private final String label;

    QuotaPeriod(String label) {
        this.label = label;
    }

    /** French wording shown to people (« par mois », « par jour », « à vie »); the code stays the API value. */
    public String label() {
        return label;
    }

    /** The fixed order in which an increment checks the periods: the daily cap first. */
    public static final List<QuotaPeriod> CHECK_ORDER = List.of(DAY, MONTH, LIFETIME);

    /** Key of the LIFETIME counter. */
    public static final String LIFETIME_KEY = "LIFETIME";

    /** The counter key of the period containing {@code today}. */
    public String key(LocalDate today) {
        return switch (this) {
            case MONTH -> YearMonth.from(today).toString();
            case DAY -> today.toString();
            case LIFETIME -> LIFETIME_KEY;
        };
    }

    /** When the period containing {@code today} ends (start of the next day / month in {@code zone}); null for LIFETIME. */
    public Instant resetAt(LocalDate today, ZoneId zone) {
        return switch (this) {
            case MONTH -> today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
            case DAY -> today.plusDays(1).atStartOfDay(zone).toInstant();
            case LIFETIME -> null;
        };
    }

    /** Whether a purchased pack may pay an increment once this period's quota is exhausted. */
    public boolean payableFromCredits() {
        return this != DAY;
    }
}
