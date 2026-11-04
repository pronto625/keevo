package com.keevo.identity.auth.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Usernames — the single rule for a member's username (Story 19.4), shared by employee creation and a future rename.
 *
 * <p>Bref's format: 3 to 50 letters, digits, {@code .}, {@code _} or {@code -} (the V41 {@code users_username_format}
 * CHECK allows 1–50 for imported Bref rows; a new username needs at least 3, as Bref's screens). Stored as typed,
 * compared case-insensitively in SQL (the {@code uq_users_username_lower} index). The {@code google-}
 * and {@code apple-} prefixes are reserved for the technical usernames of social accounts (Story 19.3) and are
 * refused, whatever their case.
 */
public final class Usernames {

    /** French message of an invalid username (422 {@code VALIDATION_FAILED}). */
    public static final String INVALID_MESSAGE =
            "Identifiant invalide (3 à 50 lettres, chiffres, point, tiret ou souligné)";

    /**
     * French message when neither a phone nor a username is given (login, employee creation) or, at login, both
     * (422 {@code VALIDATION_FAILED}).
     */
    public static final String IDENTIFIER_REQUIRED_MESSAGE = "Indiquez un numéro de téléphone ou un identifiant";

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    private static final String[] RESERVED_PREFIXES = {"google-", "apple-"};

    private Usernames() {
    }

    /** True when the username matches the format and does not start with a reserved prefix. */
    public static boolean isValid(String username) {
        return username != null && FORMAT.matcher(username).matches() && !isTechnical(username);
    }

    /**
     * Returns the stripped username, or throws {@code VALIDATION_FAILED} with {@link #INVALID_MESSAGE} when it is not a
     * valid new username.
     */
    public static String requireValid(String username) {
        String stripped = User.normalizeIdentifier(username);
        if (!isValid(stripped)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, INVALID_MESSAGE);
        }
        return stripped;
    }

    /** True for a technical username of a social account ({@code google-…}, {@code apple-…}, any case). */
    public static boolean isTechnical(String username) {
        if (username == null) {
            return false;
        }
        String lower = username.toLowerCase(Locale.ROOT);
        for (String prefix : RESERVED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
