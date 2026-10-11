package com.keevo.shared.domain;

import java.nio.charset.StandardCharsets;

/**
 * PasswordLimits — BCrypt input limit (Story 12.12).
 *
 * <p>Spring Security 6.5 {@code BCrypt} throws {@link IllegalArgumentException} for inputs over
 * 72 bytes. DTO {@code @Size(max = 72)} counts characters, so a multi-byte password can pass it
 * and still exceed 72 bytes — services check bytes before any {@code encode}/{@code matches}.
 */
public final class PasswordLimits {

    public static final int MAX_BCRYPT_BYTES = 72;

    private PasswordLimits() {}

    /** {@code true} if the password is longer than 72 UTF-8 bytes ({@code null} is not too long). */
    public static boolean exceedsBcryptLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES;
    }
}
