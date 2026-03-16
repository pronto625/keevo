package com.keevo.identity.employee.domain.model;

import java.security.SecureRandom;

/**
 * TempPasswordFactory — Factory Method pattern.
 *
 * <p>Generates a cryptographically random temporary password: 12 characters,
 * alphanumeric (excluding ambiguous O/0/I/l/1), always contains at least
 * one digit and one uppercase letter.
 *
 * <p>Story 3.5 — AC1.
 */
public final class TempPasswordFactory {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWERS = "abcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPERS + LOWERS + DIGITS;
    private static final int LENGTH = 12;

    private TempPasswordFactory() {}

    /**
     * Generate a 12-character temporary password.
     * Guaranteed to contain at least one uppercase letter and one digit.
     */
    public static String generate() {
        char[] password = new char[LENGTH];

        // Guarantee at least one uppercase and one digit at random positions
        password[0] = UPPERS.charAt(RANDOM.nextInt(UPPERS.length()));
        password[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));

        // Fill the rest with random characters from the full set
        for (int i = 2; i < LENGTH; i++) {
            password[i] = ALL.charAt(RANDOM.nextInt(ALL.length()));
        }

        // Fisher-Yates shuffle to randomize positions
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = password[i];
            password[i] = password[j];
            password[j] = temp;
        }

        return new String(password);
    }
}
