package com.keevo.identity.api;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PlanType — the code of a commercial plan ({@code public.plans.code}, Story 19.7a: plans are data).
 *
 * <p>No longer a closed list: any code of the plan catalogue is a plan, and every behaviour that depends on a plan
 * reads its <b>kind</b> ({@link PlanKind}) from the catalogue ({@code shared.capability.entitlement.PlanCatalog}),
 * never its code. A {@code PlanType} only checks the code's shape; whether the plan exists is the catalogue's
 * answer ({@code PlanCatalog.require}).
 *
 * <p>The three plans seeded by V42 are kept as well-known constants for the seeds, the catalogue's built-in fallback,
 * the V1 compatibility mapping ({@link PlanKind#v1Plan()}) and the tests — never to drive behaviour.
 *
 * <p>Pure Java value type — NO framework imports. {@link #name()} is the code (kept from the former enum).
 */
public final class PlanType {

    /** Shape of a plan code ({@code public.plans_code_check}). */
    public static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    /** The free plan seeded by V42 (kind {@link PlanKind#FREE}). */
    public static final PlanType FREE = new PlanType("FREE", PlanKind.FREE);
    /** The 6-month trial seeded by V42 (kind {@link PlanKind#TRIAL}). */
    public static final PlanType PREMIUM_TRIAL = new PlanType("PREMIUM_TRIAL", PlanKind.TRIAL);
    /** The paid plan seeded by V42 (kind {@link PlanKind#PAID}). */
    public static final PlanType PREMIUM = new PlanType("PREMIUM", PlanKind.PAID);

    private final String code;
    /** The kind of a well-known seeded plan, {@code null} for any other code. */
    private final PlanKind seedKind;

    private PlanType(String code, PlanKind seedKind) {
        this.code = code;
        this.seedKind = seedKind;
    }

    /**
     * The plan type of a code (shape check only — existence is the catalogue's answer). The seeded codes return
     * their constant.
     *
     * @throws IllegalArgumentException null or malformed code (the message never echoes the raw value)
     */
    public static PlanType of(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid plan code");
        }
        for (PlanType known : new PlanType[] {FREE, PREMIUM_TRIAL, PREMIUM}) {
            if (known.code.equals(code)) {
                return known;
            }
        }
        return new PlanType(code, null);
    }

    /** {@link #of(String)} without the exception: empty for a null or malformed code. */
    public static Optional<PlanType> parse(String code) {
        try {
            return Optional.of(of(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The plan code. */
    public String code() {
        return code;
    }

    /** The plan code (name kept from the former enum so callers compile unchanged). */
    public String name() {
        return code;
    }

    /**
     * The kind the V42 seed gives this code when it is one of the three well-known plans; empty otherwise. Only for
     * the catalogue's built-in fallback and tests: live behaviour reads the kind from the catalogue.
     */
    public Optional<PlanKind> seedKind() {
        return Optional.ofNullable(seedKind);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanType other && code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
