package com.keevo.assistant.guided.domain.model;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Story 21.4 — the periods of Bref Point's guided topics, with Bref's previous period for the comparison: today vs
 * yesterday, week (the last 7 days) vs the 7 days before, month (1st → today) vs the whole previous month, year
 * (1 January → today) vs the whole previous year. Days are civil days of the shop's zone (WAT).
 */
public enum AssistantPeriod {

    TODAY("today", "aujourd'hui", "hier"),
    WEEK("week", "cette semaine", "la semaine précédente"),
    MONTH("month", "ce mois", "le mois précédent"),
    YEAR("year", "cette année", "l'année précédente");

    /** An inclusive range of civil days. */
    public record Range(LocalDate from, LocalDate to) {
    }

    private final String id;
    private final String label;
    private final String previousLabel;

    AssistantPeriod(String id, String label, String previousLabel) {
        this.id = id;
        this.label = label;
        this.previousLabel = previousLabel;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String previousLabel() {
        return previousLabel;
    }

    public static Optional<AssistantPeriod> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.id.equals(key)).findFirst();
    }

    public Range current(LocalDate today) {
        return switch (this) {
            case TODAY -> new Range(today, today);
            case WEEK -> new Range(today.minusDays(6), today);
            case MONTH -> new Range(today.withDayOfMonth(1), today);
            case YEAR -> new Range(today.withDayOfYear(1), today);
        };
    }

    public Range previous(LocalDate today) {
        return switch (this) {
            case TODAY -> new Range(today.minusDays(1), today.minusDays(1));
            case WEEK -> new Range(today.minusDays(13), today.minusDays(7));
            case MONTH -> {
                LocalDate start = today.withDayOfMonth(1).minusMonths(1);
                yield new Range(start, start.withDayOfMonth(start.lengthOfMonth()));
            }
            case YEAR -> {
                LocalDate start = today.withDayOfYear(1).minusYears(1);
                yield new Range(start, start.withDayOfYear(start.lengthOfYear()));
            }
        };
    }
}
