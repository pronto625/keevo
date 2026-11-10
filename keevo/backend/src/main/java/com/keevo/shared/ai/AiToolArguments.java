package com.keevo.shared.ai;

import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;

import java.time.LocalDate;

/**
 * Story 21.8 — checks shared by the AI tools on arguments a model chooses: a date range must be ordered and stay within
 * plausible business years (a model can send any date; an absurd one would otherwise fail deep in the database as an
 * internal error). French messages, answered as {@code VALIDATION_ERROR}.
 */
public final class AiToolArguments {

    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;

    private AiToolArguments() {
    }

    /** @throws InvalidRequestParameterException {@code from} after {@code to}, or a date outside 2000–2100 */
    public static void checkPeriod(LocalDate from, LocalDate to) {
        checkDate("from", from);
        checkDate("to", to);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from after to",
                    "La date de début doit précéder la date de fin.");
        }
    }

    private static void checkDate(String name, LocalDate date) {
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw new InvalidRequestParameterException(name, "date out of range",
                    "Date hors limites : choisissez une date entre " + MIN_YEAR + " et " + MAX_YEAR + ".");
        }
    }
}
