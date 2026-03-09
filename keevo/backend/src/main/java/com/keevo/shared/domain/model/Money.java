package com.keevo.shared.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money — XAF value object (CFA Franc).
 *
 * <p>Rules (NON-NEGOTIABLE):
 * <ul>
 *   <li>Stored as {@code int} — XAF has NO sub-units (no decimals).
 *   <li>Value MUST be >= 0; negative values throw {@link DomainException}("INVALID_AMOUNT").
 *   <li>{@link #toString()} returns {@code "5000 XAF"}.
 *   <li>Arithmetic methods return new {@link Money} instances (immutable).
 * </ul>
 *
 * <p>Pure Java — NO Spring/JPA imports.
 */
public final class Money {

    private final int value;

    public Money(int value) {
        if (value < 0) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT,
                    "Monetary value cannot be negative: " + value);
        }
        this.value = value;
    }

    public int value() {
        return value;
    }

    public Money add(Money other) {
        return new Money(this.value + other.value);
    }

    public Money subtract(Money other) {
        if (other.value > this.value) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT,
                    "Subtraction would result in negative amount");
        }
        return new Money(this.value - other.value);
    }

    public Money multiply(int factor) {
        if (factor < 0) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT,
                    "Multiplication factor cannot be negative: " + factor);
        }
        return new Money(this.value * factor);
    }

    /**
     * Multiply by a double factor (e.g. 1.3 for +30% margin).
     * Uses BigDecimal.HALF_UP rounding — XAF has no sub-units.
     */
    public Money multiply(double factor) {
        if (factor < 0) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT,
                    "Multiplication factor cannot be negative: " + factor);
        }
        int result = BigDecimal.valueOf(this.value)
                .multiply(BigDecimal.valueOf(factor))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        return new Money(result);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return value == money.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return value + " XAF";
    }
}
