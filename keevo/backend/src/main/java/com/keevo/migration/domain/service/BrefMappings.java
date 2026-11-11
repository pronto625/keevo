package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.Role;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.shared.domain.PhoneNumbers;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Story 24.2 — the Bref → Keevo correspondences of the identity import (pure functions, no I/O): roles, sector,
 * phone, identifiers, the functions Bref had. Values from {@code api-bref} {@code origin/develop} 6faa8b4 (journal
 * 18.3: no schema change since the 03/11 production).
 */
public final class BrefMappings {

    /** Bref member roles ({@code UserRole}) → Keevo membership roles (19.5). */
    private static final Map<String, Role> MEMBER_ROLES = Map.of(
            "admin", Role.MANAGER,
            "seller", Role.EMPLOYEE,
            "viewer", Role.VIEWER);

    public static final String BREF_OWNER_ROLE = "owner";

    /**
     * Decision D4 — the Keevo modules matching what Bref offers (documents, catalogue, clients, expenses, finance,
     * reports, WhatsApp Business, assistant, client portal); every other owner-toggleable module starts owner-disabled
     * for an imported owner (the DOCUMENT rules already disable till, stock, counting and transfers). Entitlement stays
     * with the plan (24.5).
     */
    public static final Set<String> BREF_EQUIVALENT_MODULES = Set.of("catalog", "crm", "invoicing", "expenses",
            "finance", "reporting", "whatsapp-cloud", "ai-assistant", "client-portal");

    /** Bref shop category ({@code ShopCategory}) → onboarding sector ({@code SectorType}). */
    private static final Map<String, String> SECTORS = Map.ofEntries(
            Map.entry("fashion", "CLOTHING"),
            Map.entry("electronics", "ELECTRONICS"),
            Map.entry("telecom", "ELECTRONICS"),
            Map.entry("stationery", "BOOKS_STATIONERY"),
            Map.entry("education", "BOOKS_STATIONERY"),
            Map.entry("home", "HOME_APPLIANCES"),
            Map.entry("furniture", "HOME_APPLIANCES"),
            Map.entry("food", "FOOD_GROCERY"),
            Map.entry("restaurant", "FOOD_GROCERY"),
            Map.entry("agriculture", "FOOD_GROCERY"),
            Map.entry("hardware", "HARDWARE"),
            Map.entry("automotive", "HARDWARE"));

    /** The {@code users_username_format} CHECK (V41): imported Bref usernames may be 1–50 characters. */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{1,50}$");
    /** bcrypt as Spring's {@code BCryptPasswordEncoder} accepts it ({@code $2a$}, {@code $2b$}, {@code $2y$}). */
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    public static final int MAX_EMAIL = 254;
    public static final int MAX_NAME = 100;
    public static final String MEMBER_FALLBACK_NAME = "Membre";

    private BrefMappings() {
    }

    public static Optional<Role> memberRole(String brefRole) {
        return brefRole == null ? Optional.empty() : Optional.ofNullable(MEMBER_ROLES.get(brefRole.strip()));
    }

    /** The sector of a shop category; {@code OTHER} for any other or unknown category, {@code null} for none. */
    public static String sector(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return SECTORS.getOrDefault(category.strip().toLowerCase(Locale.ROOT), "OTHER");
    }

    /** The E.164 form of a Bref phone (9-digit local numbers are Cameroonian); {@code null} when blank or invalid. */
    public static String e164(String raw) {
        return PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON);
    }

    /**
     * A username made only of digits (optionally {@code +}), read as a phone in E.164 — the form a sign-in would
     * compare with the phones; {@code null} for any other username. Same rule as the identity conflict query.
     */
    public static String usernameAsPhone(String username) {
        String u = trimmed(username);
        return u != null && u.matches("\\+?[0-9]{8,15}") ? e164(u) : null;
    }

    /** Whether a phone is given but cannot be read as E.164. */
    public static boolean invalidPhone(String raw) {
        return trimmed(raw) != null && e164(raw) == null;
    }

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public static boolean supportedHash(String hash) {
        return hash == null || BCRYPT.matcher(hash).matches();
    }

    /**
     * Story 24.3 — the sector of an owner: the category of the oldest shop that has one (live shops before deleted
     * ones); {@code null} when none. The profile (24.2) and the categories step (24.3) share this choice.
     */
    public static String ownerSector(java.util.List<BrefShop> shops) {
        return shops.stream()
                .sorted(java.util.Comparator.comparing(BrefShop::deleted).thenComparing(BrefShop::createdAt)
                        .thenComparing(BrefShop::id))
                .map(s -> sector(s.category()))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** Story 24.3 — a UUID written as text by Bref (trimmed, any case); {@code null} when blank or malformed. */
    public static java.util.UUID uuidOrNull(String raw) {
        String v = trimmed(raw);
        if (v == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Story 24.3 — {@code value} cut to {@code max} code points (never a half surrogate pair). */
    public static String cut(String value, int max) {
        if (value == null || value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** Trimmed value, {@code null} when blank. */
    public static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The account as Keevo stores it: E.164 phone, trimmed identifiers and names, verification dates from the Bref
     * flags (date = the row's {@code updatedAt}, the only date Bref keeps).
     */
    public static LegacyAccount toLegacy(BrefAccount a) {
        String phone = e164(a.phone());
        String email = trimmed(a.email());
        String provider = socialProvider(a);
        return new LegacyAccount(a.id(), phone, email, trimmed(a.username()), trimmed(a.passwordHash()),
                trimmed(a.firstName()), trimmed(a.lastName()),
                a.emailVerified() && email != null ? a.updatedAt() : null,
                a.verified() && phone != null ? a.updatedAt() : null,
                a.createdAt(), a.updatedAt(), provider, provider == null ? null : trimmed(a.socialId()));
    }

    /** Story 24.7 — Bref's social providers ({@code AuthProvidersEnum}) → Keevo authenticator providers (19.3). */
    private static final Map<String, String> SOCIAL_PROVIDERS = Map.of("google", "GOOGLE", "apple", "APPLE");

    /** The longest subject Keevo keeps ({@code user_authenticators.subject}). */
    public static final int MAX_SOCIAL_SUBJECT = 255;

    /**
     * Story 24.7 — the Keevo provider of a Bref account signing in with Google or Apple ({@code GOOGLE} /
     * {@code APPLE}); {@code null} for an email / code account, an unknown provider, or a missing or oversized
     * {@code socialId}.
     */
    public static String socialProvider(BrefAccount a) {
        String provider = trimmed(a.provider());
        String subject = trimmed(a.socialId());
        if (provider == null || subject == null || subject.length() > MAX_SOCIAL_SUBJECT) {
            return null;
        }
        return SOCIAL_PROVIDERS.get(provider.toLowerCase(Locale.ROOT));
    }

    /** First name of a member's employee record: the first name, else the username, else « Membre ». */
    public static String employeeFirstName(BrefAccount a) {
        String first = trimmed(a.firstName());
        if (first != null) {
            return first;
        }
        String username = trimmed(a.username());
        return username != null ? username : MEMBER_FALLBACK_NAME;
    }

    /** Last name of a member's employee record (the column is NOT NULL): the last name or empty. */
    public static String employeeLastName(BrefAccount a) {
        String last = trimmed(a.lastName());
        return last != null ? last : "";
    }
}
