package com.keevo.sync.sync.application.provider;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/**
 * DeltaValues — type-checked conversion of native-query column values for the delta
 * providers (v1s-15-9). Replaces the private {@code num}/{@code bool} copies that cast blindly
 * with a bare Number-to-int narrowing (silent truncation, ClassCastException on a String).
 *
 * <p>Numbers are never truncated: integral values become an {@code int} when they fit, else a
 * {@code long}; non-integral values are returned unchanged (lossless beats a failed pull).
 */
final class DeltaValues {

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private DeltaValues() {
    }

    /**
     * Numeric column → JSON value. {@code null} → 0 (the providers' existing contract).
     *
     * @throws IllegalArgumentException when {@code o} is not a {@link Number}
     */
    static Object num(Object o) {
        if (o == null) return 0;
        if (o instanceof Integer i) return i;
        if (o instanceof Short s) return s.intValue();
        if (o instanceof Byte b) return b.intValue();
        if (o instanceof Long l) {
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) l.intValue() : l;
        }
        if (o instanceof BigInteger bi) return narrow(bi, o);
        if (o instanceof BigDecimal bd) return integralOrSelf(bd, o);
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return o;
            return integralOrSelf(new BigDecimal(o.toString()), o);
        }
        throw new IllegalArgumentException("Unsupported numeric value type: " + o.getClass().getName());
    }

    /**
     * Boolean column → JSON boolean. {@code null} → false. Accepts Boolean, numbers (≠ 0) and the
     * PostgreSQL text forms ({@code t/f}, {@code true/false}, {@code 1/0}, {@code y/n}, {@code yes/no}).
     *
     * @throws IllegalArgumentException on an unknown type or unrecognised text
     */
    static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof BigDecimal bd) return bd.signum() != 0;
        if (o instanceof BigInteger bi) return bi.signum() != 0;
        if (o instanceof Double || o instanceof Float) return ((Number) o).doubleValue() != 0d;
        if (o instanceof Number n) return n.longValue() != 0L;
        if (o instanceof String s) {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "t", "true", "1", "y", "yes" -> { return true; }
                case "f", "false", "0", "n", "no" -> { return false; }
                default -> throw new IllegalArgumentException("Unrecognised boolean value: " + s);
            }
        }
        if (o instanceof Character c) return bool(String.valueOf(c));
        throw new IllegalArgumentException("Unsupported boolean value type: " + o.getClass().getName());
    }

    private static Object integralOrSelf(BigDecimal bd, Object original) {
        BigDecimal stripped = bd.stripTrailingZeros();
        if (stripped.scale() > 0) return original;
        return narrow(stripped.toBigIntegerExact(), original);
    }

    private static Object narrow(BigInteger bi, Object original) {
        if (bi.compareTo(INT_MIN) >= 0 && bi.compareTo(INT_MAX) <= 0) return bi.intValue();
        if (bi.compareTo(LONG_MIN) >= 0 && bi.compareTo(LONG_MAX) <= 0) return bi.longValue();
        return original;
    }
}
