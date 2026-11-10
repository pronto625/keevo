package com.keevo.shared.ai;

import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Story 21.8 — checks shared by the AI tools on arguments a model chooses: a date range must be ordered and stay within
 * plausible business years (a model can send any date; an absurd one would otherwise fail deep in the database as an
 * internal error). French messages, answered as {@code VALIDATION_ERROR}.
 */
public final class AiToolArguments {

    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;
    /** Longest period a tool aggregates (two years: « 2025 et 2026 »); beyond, a full-history scan per call. */
    public static final int MAX_PERIOD_DAYS = 731;
    /** Longest search text of a tool. */
    public static final int MAX_SEARCH_LENGTH = 100;
    /** Highest page a paged tool serves (a huge page is a long OFFSET scan for nothing). */
    public static final int MAX_PAGE = 1_000;

    private AiToolArguments() {
    }

    /**
     * @throws InvalidRequestParameterException {@code from} after {@code to}, a date outside 2000–2100, or a period
     *                                          longer than {@value #MAX_PERIOD_DAYS} days
     */
    public static void checkPeriod(LocalDate from, LocalDate to) {
        checkDate("from", from);
        checkDate("to", to);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from after to",
                    "La date de début doit précéder la date de fin.");
        }
        if (from != null && to != null && ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new InvalidRequestParameterException("to", "period too long",
                    "Période trop longue : choisissez au plus deux ans.");
        }
    }

    /**
     * A search text: {@code null} when blank, else stripped; refused when it holds a NUL character (PostgreSQL rejects
     * it) or exceeds {@value #MAX_SEARCH_LENGTH} characters once stripped — one rule and one message for every tool.
     */
    public static String search(String search) {
        String text = search == null || search.isBlank() ? null : search.strip();
        if (text != null && text.indexOf('\0') >= 0) {
            throw new InvalidRequestParameterException("search", "NUL character in search",
                    "La recherche contient un caractère non autorisé.");
        }
        if (text != null && text.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidRequestParameterException("search", "search too long",
                    "La recherche ne doit pas dépasser " + MAX_SEARCH_LENGTH + " caractères");
        }
        return text;
    }

    /** A page number between 0 and {@value #MAX_PAGE} ({@code null} or negative = 0). */
    public static int page(Integer page) {
        return page == null || page < 0 ? 0 : Math.min(page, MAX_PAGE);
    }

    private static void checkDate(String name, LocalDate date) {
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw new InvalidRequestParameterException(name, "date out of range",
                    "Date hors limites : choisissez une date entre " + MIN_YEAR + " et " + MAX_YEAR + ".");
        }
    }
}
