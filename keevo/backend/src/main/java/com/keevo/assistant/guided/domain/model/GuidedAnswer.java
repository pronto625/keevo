package com.keevo.assistant.guided.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Story 21.4 — the answer to a guided topic, ready to show: a French title and sentence, key figures, and rows
 * (clients, stores, sellers, payment methods…). Raw numbers for the app to format; no identifier.
 *
 * @param period the period answered ({@code null} for a topic « à date »)
 */
public record GuidedAnswer(String topic, String title, String text, Period period, List<Stat> stats, List<Row> rows) {

    public GuidedAnswer {
        stats = List.copyOf(stats);
        rows = List.copyOf(rows);
    }

    /** @param id {@code today}, {@code week}, {@code month}, {@code year} */
    public record Period(String id, String label, LocalDate from, LocalDate to) {
    }

    /**
     * A key figure.
     *
     * @param unit {@code FCFA}, {@code COUNT} or {@code PERCENT}
     */
    public record Stat(String key, String label, Number value, String unit) {
    }

    /**
     * A line of a list.
     *
     * @param amount main amount in FCFA ({@code null} when none)
     * @param detail French secondary text ({@code null} when none)
     * @param phone  phone number to call ({@code null} when unknown)
     */
    public record Row(String label, Long amount, String detail, String phone) {
    }
}
