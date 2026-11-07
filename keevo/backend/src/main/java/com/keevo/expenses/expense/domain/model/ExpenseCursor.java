package com.keevo.expenses.expense.domain.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 20.10 — keyset position of the expense list: the sort keys of the last row of a page. Opaque to the client
 * (URL-safe Base64 of {@code sort~date~createdAt|amount~id}); bound to its sort: a cursor of another order, or a
 * tampered one, is refused ({@link IllegalArgumentException}).
 */
public record ExpenseCursor(ExpenseSort sort, LocalDate date, Instant createdAt, BigDecimal amount, UUID id) {

    private static final String SEPARATOR = "~";

    public ExpenseCursor {
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(id, "id");
        if (sort.keys() == ExpenseSort.Keys.BY_DATE) {
            Objects.requireNonNull(createdAt, "createdAt");
        } else {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** The position right after {@code last} in {@code sort}. */
    public static ExpenseCursor after(ExpenseSort sort, Expense last) {
        boolean byDate = sort.keys() == ExpenseSort.Keys.BY_DATE;
        return new ExpenseCursor(sort, last.expenseDate(), byDate ? last.createdAt() : null,
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
        String raw = String.join(SEPARATOR, sort.key(), date.toString(), middle, id.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException malformed, tampered or of another sort than {@code expected}
     */
    public static ExpenseCursor decode(String token, ExpenseSort expected) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR, -1);
            if (parts.length != 4 || !expected.key().equals(parts[0])) {
                throw new IllegalArgumentException("cursor of another sort");
            }
            LocalDate date = LocalDate.parse(parts[1]);
            UUID id = UUID.fromString(parts[3]);
            if (expected.keys() == ExpenseSort.Keys.BY_DATE) {
                return new ExpenseCursor(expected, date, Instant.parse(parts[2]), null, id);
            }
            return new ExpenseCursor(expected, date, null, new BigDecimal(parts[2]), id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed expense cursor", e);
        }
    }
}
