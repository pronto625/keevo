package com.keevo.expenses.expense.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Story 20.10 — the editable content of an expense, built only through {@link #of}, which applies Bref's rules
 * exactly ({@code api-bref/src/expense}: {@code CreateExpenseDto}, {@code expense-date.ts}, {@code ExpenseService}):
 * <ul>
 *   <li>category: one of the 10 keys;</li>
 *   <li>amount: 0.01 … 999 999 999 999, at most 2 decimals;</li>
 *   <li>date: {@code yyyy-MM-dd}, an existing calendar date (2026-02-31 refused), at most one day after today in UTC
 *       (Bref tolerates time zones, not year typos), today in UTC when absent; no lower bound;</li>
 *   <li>label (Bref {@code description}): trimmed, blank = none, ≤ 500; note: trimmed, blank = none, ≤ 1000;</li>
 *   <li>payment method: optional, one of the 5 keys.</li>
 * </ul>
 * Any violation → {@code VALIDATION_ERROR} with a French message (HTTP 422, sync {@code REJECTED}).
 */
public record ExpenseContent(
        ExpenseCategory category,
        BigDecimal amount,
        LocalDate expenseDate,
        String label,
        String note,
        ExpensePaymentMethod paymentMethod
) {

    public static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999");
    public static final int MAX_LABEL = 500;
    public static final int MAX_NOTE = 1000;

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /** Validates the raw input (keys and texts as received). */
    public static ExpenseContent of(String categoryKey, BigDecimal amount, String expenseDate, String label,
                                    String note, String paymentMethodKey, Clock clock) {
        ExpenseCategory category = ExpenseCategory.fromKey(categoryKey)
                .orElseThrow(() -> invalid("category", "Catégorie de dépense inconnue"));
        ExpensePaymentMethod method = null;
        if (paymentMethodKey != null) {
            method = ExpensePaymentMethod.fromKey(paymentMethodKey)
                    .orElseThrow(() -> invalid("paymentMethod", "Mode de paiement inconnu"));
        }
        return new ExpenseContent(category, amount(amount), date(expenseDate, clock),
                text(label, MAX_LABEL, "label", "Le libellé ne doit pas dépasser " + MAX_LABEL + " caractères"),
                text(note, MAX_NOTE, "note", "La note ne doit pas dépasser " + MAX_NOTE + " caractères"), method);
    }

    static BigDecimal amount(BigDecimal amount) {
        if (amount == null) {
            throw invalid("amount", "Le montant est requis");
        }
        if (amount.compareTo(MIN_AMOUNT) < 0) {
            throw invalid("amount", "Le montant doit être d'au moins 0,01");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("amount", "Le montant est trop élevé");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw invalid("amount", "Le montant accepte au plus 2 décimales");
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** Bref {@code isIsoDate} + {@code isTooFarInFuture} + default {@code todayIso()} (all in UTC). */
    static LocalDate date(String raw, Clock clock) {
        if (raw == null) {
            return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        }
        LocalDate date;
        try {
            if (!ISO_DATE.matcher(raw).matches()) {
                throw new DateTimeParseException("format", raw, 0);
            }
            date = LocalDate.parse(raw, STRICT_DATE);
        } catch (DateTimeParseException e) {
            throw invalid("expenseDate", "Date de dépense invalide (format attendu AAAA-MM-JJ)");
        }
        LocalDate limit = LocalDate.ofInstant(clock.instant().plus(1, ChronoUnit.DAYS), ZoneOffset.UTC);
        if (date.isAfter(limit)) {
            throw invalid("expenseDate", "La date de la dépense ne peut pas dépasser demain");
        }
        return date;
    }

    private static String text(String raw, int max, String field, String tooLong) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > max) {
            throw invalid(field, tooLong);
        }
        return value;
    }

    private static DomainException invalid(String field, String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of("field", field));
    }
}
