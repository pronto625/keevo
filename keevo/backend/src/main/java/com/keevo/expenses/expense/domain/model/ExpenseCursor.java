package com.keevo.expenses.expense.domain.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 20.10 — keyset position of the expense list: the sort keys of the last row of a page, bound to the list's
 * filters. Opaque to the client (URL-safe Base64 of {@code filters~date~createdAt|amount~id}, where {@code filters} is
 * a short hash of storeId, category, from, to, search and sort). Decoding refuses ({@link IllegalArgumentException})
 * a malformed or tampered cursor, values outside the column ranges, and a cursor of other filters.
 */
public record ExpenseCursor(String filters, ExpenseSort sort, LocalDate date, Instant createdAt, BigDecimal amount,
                            UUID id) {

    private static final String SEPARATOR = "~";
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    private static final Instant MIN_INSTANT = Instant.parse("1900-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    public ExpenseCursor {
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(id, "id");
        if (sort.keys() == ExpenseSort.Keys.BY_DATE) {
            Objects.requireNonNull(createdAt, "createdAt");
        } else {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * The hash naming a list's filters (16 hex characters of SHA-256 over the normalised values); the sort is part of
     * it, so a cursor never moves to another order.
     */
    public static String filters(UUID storeId, ExpenseCategory category, LocalDate from, LocalDate to, String search,
                                 ExpenseSort sort) {
        String canonical = String.join("\u0001", String.valueOf(storeId),
                category == null ? "" : category.key(), String.valueOf(from), String.valueOf(to),
                search == null ? "" : search, sort.key());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The position right after {@code last} in {@code sort}, for the list named by {@code filters}. */
    public static ExpenseCursor after(String filters, ExpenseSort sort, Expense last) {
        boolean byDate = sort.keys() == ExpenseSort.Keys.BY_DATE;
        return new ExpenseCursor(filters, sort, last.expenseDate(), byDate ? last.createdAt() : null,
                byDate ? null : last.amount(), last.id());
    }

    /** The key values in the order of {@link ExpenseSort.Keys#columns()}. */
    public List<Object> values() {
        return sort.keys() == ExpenseSort.Keys.BY_DATE
                ? List.of(date, createdAt, id)
                : List.of(amount, date, id);
    }

    public String encode() {
        String middle = sort.keys() == ExpenseSort.Keys.BY_DATE ? createdAt.toString() : amount.toPlainString();
        String raw = String.join(SEPARATOR, filters, date.toString(), middle, id.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param expectedFilters {@link #filters} of the current request (its sort included)
     * @throws IllegalArgumentException malformed, tampered, out of range or of other filters
     */
    public static ExpenseCursor decode(String token, String expectedFilters, ExpenseSort sort) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR, -1);
            if (parts.length != 4 || !expectedFilters.equals(parts[0])) {
                throw new IllegalArgumentException("cursor of other filters");
            }
            LocalDate date = LocalDate.parse(parts[1]);
            if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
                throw new IllegalArgumentException("date out of range");
            }
            UUID id = UUID.fromString(parts[3]);
            if (sort.keys() == ExpenseSort.Keys.BY_DATE) {
                Instant createdAt = Instant.parse(parts[2]);
                if (createdAt.isBefore(MIN_INSTANT) || createdAt.isAfter(MAX_INSTANT)) {
                    throw new IllegalArgumentException("instant out of range");
                }
                return new ExpenseCursor(expectedFilters, sort, date, createdAt, null, id);
            }
            BigDecimal amount = new BigDecimal(parts[2]);
            if (amount.signum() < 0 || amount.compareTo(ExpenseContent.MAX_AMOUNT) > 0
                    || amount.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("amount out of range");
            }
            return new ExpenseCursor(expectedFilters, sort, date, null, amount, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed expense cursor", e);
        }
    }
}
