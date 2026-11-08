package com.keevo.finance.summary.domain.model;

import com.keevo.finance.summary.domain.model.FinanceMath.Period;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Story 20.11 — the period of a finance summary and its comparison period, from the request:
 * <ul>
 *   <li>{@code period=yyyy-MM} (that month) or {@code yyyy} (that year) — the two modes of the Bref screen; compared
 *       with the previous calendar month / year;</li>
 *   <li>or Bref's {@code startDate} / {@code endDate} ({@code yyyy-MM-dd}; each defaults to the first / last day of the
 *       current month — WAT civil date, FUS-NFR9); compared, as Bref does, with the window of the same length just
 *       before.</li>
 * </ul>
 * Dates between 2000 and 2100. Refusals carry the parameter and a French message (the REST adapter answers 400).
 */
public final class FinancePeriods {

    /** Bref's {@code MAX_PERIOD_DAYS}. */
    public static final int MAX_PERIOD_DAYS = 366;
    public static final int MIN_YEAR = 2000;
    public static final int MAX_YEAR = 2100;

    static final String BAD_PERIOD = "Période invalide : utilisez AAAA-MM (un mois) ou AAAA (une année).";
    static final String BOTH_FORMS = "Indiquez soit une période, soit des dates de début et de fin, pas les deux.";
    static final String BAD_DATE = "Date invalide : utilisez le format AAAA-MM-JJ.";
    static final String INVERTED = "La date de début doit précéder la date de fin.";
    static final String TOO_LONG = "La période ne peut pas dépasser " + MAX_PERIOD_DAYS + " jours.";
    static final String OUT_OF_RANGE = "L'année doit être comprise entre " + MIN_YEAR + " et " + MAX_YEAR + ".";

    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The period asked and the one it is compared with. */
    public record Resolved(Period period, Period comparison) {
    }

    /** A refused period: the request parameter at fault and the French message for the user. */
    public static final class InvalidPeriodException extends RuntimeException {
        private final String parameter;

        InvalidPeriodException(String parameter, String message) {
            super(message);
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }
    }

    private FinancePeriods() {
    }

    /**
     * @param today the current civil date in WAT — gives the default month
     */
    public static Resolved resolve(String period, String startDate, String endDate, LocalDate today) {
        boolean hasPeriod = present(period);
        boolean hasStart = present(startDate);
        boolean hasEnd = present(endDate);
        if (hasPeriod && (hasStart || hasEnd)) {
            throw new InvalidPeriodException("period", BOTH_FORMS);
        }
        if (hasPeriod) {
            return ofPeriod(period.strip());
        }
        YearMonth current = YearMonth.from(today);
        LocalDate start = hasStart ? date("startDate", startDate) : current.atDay(1);
        LocalDate end = hasEnd ? date("endDate", endDate) : current.atEndOfMonth();
        if (start.isAfter(end)) {
            throw new InvalidPeriodException(hasStart ? "startDate" : "endDate", INVERTED);
        }
        if (FinanceMath.daysInclusive(start, end) > MAX_PERIOD_DAYS) {
            throw new InvalidPeriodException("endDate", TOO_LONG);
        }
        return new Resolved(new Period(start, end), FinanceMath.previousPeriod(start, end));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static Resolved ofPeriod(String value) {
        try {
            if (MONTH.matcher(value).matches()) {
                YearMonth month = YearMonth.parse(value);
                inRange("period", month.getYear());
                YearMonth before = month.minusMonths(1);
                return new Resolved(new Period(month.atDay(1), month.atEndOfMonth()),
                        new Period(before.atDay(1), before.atEndOfMonth()));
            }
            if (YEAR.matcher(value).matches()) {
                Year year = Year.parse(value);
                inRange("period", year.getValue());
                Year before = year.minusYears(1);
                return new Resolved(new Period(year.atDay(1), year.atMonth(12).atEndOfMonth()),
                        new Period(before.atDay(1), before.atMonth(12).atEndOfMonth()));
            }
        } catch (DateTimeParseException e) {
            // falls through to the refusal
        }
        throw new InvalidPeriodException("period", BAD_PERIOD);
    }

    /** A real calendar date in {@code yyyy-MM-dd} (Bref's {@code isIsoDate}) between 2000 and 2100, else a refusal. */
    private static LocalDate date(String parameter, String value) {
        String v = value.strip();
        LocalDate parsed = null;
        if (DATE.matcher(v).matches()) {
            try {
                parsed = LocalDate.parse(v);
            } catch (DateTimeParseException e) {
                // 2026-02-30: falls through
            }
        }
        if (parsed == null) {
            throw new InvalidPeriodException(parameter, BAD_DATE);
        }
        inRange(parameter, parsed.getYear());
        return parsed;
    }

    private static void inRange(String parameter, int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new InvalidPeriodException(parameter, OUT_OF_RANGE);
        }
    }
}
